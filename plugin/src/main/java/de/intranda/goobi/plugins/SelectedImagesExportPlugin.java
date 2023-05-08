package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.PropertyManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.ContentFileReference;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.FileSet;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class SelectedImagesExportPlugin implements IExportPlugin, IPlugin {

    @Getter
    private String title = "intranda_export_selected_images";
    @Getter
    private PluginType type = PluginType.Export;
    @Getter
    @Setter
    private Step step;

    private boolean exportMetsFile;
    private boolean createSubfolders;
    private String propertyName;
    private String sourceFolderName;
    private String targetFolder;

    private boolean useScp;
    private String scpLogin;
    private String scpPassword;

    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Getter
    private List<String> problems;

    @Override
    public void setExportFulltext(boolean arg0) {
    }

    @Override
    public void setExportImages(boolean arg0) {
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
    WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
    TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportImagesPath();
        return startExport(process, benutzerHome);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
    PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
    SwapException, DAOException, TypeNotAllowedForParentException {
        log.debug("================= STARTING TO EXPORT SELECTED IMAGES =================");

        problems = new ArrayList<>();

        // read information from config file
        initializeFields(process);

        // read mets file to test if it is readable
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = null;
            ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();
            VariableReplacer replacer = new VariableReplacer(dd, prefs, process, null);
            propertyName = replacer.replace(propertyName);
            targetFolder = replacer.replace(targetFolder);
        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        }

        // get the list of selected images
        Map<String, Integer> selectedImagesNamesOrderMap = getSelectedImagesNamesOrderMap(process);
        Map<Image, Integer> selectedImagesOrderMap = getSelectedImagesOrderMap(process, selectedImagesNamesOrderMap);

        //        boolean success = selectedImages != null;
        boolean success = selectedImagesOrderMap != null;

        // export the selected images
        success = success && exportSelectedImages(process, selectedImagesOrderMap);

        // export the mets-file
        success = success && (!exportMetsFile || exportMetsFile(process, selectedImagesNamesOrderMap));

        // check the success
        if (!success) {
            log.error("Export aborted for process with ID " + process.getId());
        } else {
            log.info("Export executed for process with ID " + process.getId());
        }
        return success;
    }

    private void initializeFields(Process process) {
        SubnodeConfiguration config = getConfig(process);
        exportMetsFile = config.getBoolean("./exportMetsFile");
        createSubfolders = config.getBoolean("./createSubfolders");
        propertyName = config.getString("./propertyName");
        sourceFolderName = config.getString("./sourceFolder").trim();
        targetFolder = config.getString("targetFolder").trim();

        useScp = config.getBoolean("./useScp", false);
        scpLogin = config.getString("./scpLogin", "");
        scpPassword = config.getString("./scpPassword", "");

        log.debug("exportMetsFile: {}", exportMetsFile ? "yes" : "no");
        log.debug("createSubfolders: {}", createSubfolders ? "yes" : "no");
        log.debug("propertyName = " + propertyName);
        log.debug("sourceFolderName = " + sourceFolderName);
        log.debug("targetFolder = " + targetFolder);
        log.debug("useScp: {}", useScp ? "yes" : "no");
    }

    private Map<Image, Integer> getSelectedImagesOrderMap(Process process, Map<String, Integer> imageNamesOrderMap)
            throws IOException, SwapException, DAOException {
        log.debug("getting selected images");
        // check the names-order map
        if (imageNamesOrderMap.isEmpty()) {
            String message = "No image is selected, aborting.";
            logBoth(process.getId(), LogType.INFO, message);
            return null;
        }

        // check source folder
        String imageFolder = process.getConfiguredImageFolder(sourceFolderName);
        if (StringUtils.isBlank(imageFolder)) {
            String message = "The folder configured as '" + sourceFolderName + "' does not exist yet. Aborting.";
            logBoth(process.getId(), LogType.INFO, message);
            return null;
        }

        // get names of all selected images
        Map<Image, Integer> selectedImagesOrderMap = new HashMap<>();

        Path imageFolderPath = Path.of(imageFolder);
        log.debug("imageFolderPath = " + imageFolderPath);
        List<Path> imagePaths = storageProvider.listFiles(imageFolder);
        log.debug("imagePaths has size " + imagePaths.size());

        Integer thumbnailSize = null;
        int order = 0;
        for (Path imagePath : imagePaths) {
            String fileName = imagePath.getFileName().toString();
            if (imageNamesOrderMap.containsKey(fileName)) {
                Image image = new Image(process, imageFolder, fileName, order++, thumbnailSize);
                selectedImagesOrderMap.put(image, imageNamesOrderMap.get(fileName));
            }
        }

        return selectedImagesOrderMap;
    }

    private Map<String, Integer> getSelectedImagesNamesOrderMap(Process process) {
        Map<String, Integer> selectedImagesNamesOrderMap = new HashMap<>();
        Processproperty property = getProcessproperty(process);
        if (property != null) {
            String propertyValue = property.getWert();
            log.debug("propertyValue = " + propertyValue);
            // remove { and } from both ends
            String reducedValue = propertyValue.substring(1, propertyValue.length() - 1);
            String[] items = reducedValue.split(",");
            for (String item : items) {
                String[] itemParts = item.split(":");
                String imageName = itemParts[0];
                // remove " from both ends
                String reducedImageName = imageName.substring(1, imageName.length() - 1);
                int imageOrder = Integer.parseInt(itemParts[1]);
                selectedImagesNamesOrderMap.put(reducedImageName, imageOrder);
            }
        }

        return selectedImagesNamesOrderMap;
    }

    private Processproperty getProcessproperty(Process process) {
        List<Processproperty> props = PropertyManager.getProcessPropertiesForProcess(process.getId());
        for (Processproperty p : props) {
            if (propertyName.equals(p.getTitel())) {
                return p;
            }
        }
        return null;
    }

    private boolean exportSelectedImages(Process process, Map<Image, Integer> selectedImagesMap) {
        int processId = process.getId();
        return useScp ? exportSelectedImagesUsingScp(processId, selectedImagesMap) : exportSelectedImagesLocally(processId, selectedImagesMap);
    }

    // ================= EXPORT USING SCP ================= // 
    private boolean exportSelectedImagesUsingScp(int processId, Map<Image, Integer> selectedImagesMap) {
        Path targetFolderPath = Path.of(targetFolder);
        // create subfolders if configured so
        boolean success = !createSubfolders || createSubfoldersUsingScp(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImagesMap.keySet()) {
            success = success && exportImageUsingScp(processId, image, targetFolderPath);
        }

        return success;
    }

    private boolean createSubfoldersUsingScp(int processId, Path folderPath) {

        return true;
    }

    private boolean exportImageUsingScp(int processId, Image image, Path targetFolderPath) {

        return true;
    }
    // =============== // EXPORT USING SCP // =============== //

    // ================= EXPORT LOCALLY ================= // 
    private boolean exportSelectedImagesLocally(int processId, Map<Image, Integer> selectedImagesMap) {
        Path targetFolderPath = Path.of(targetFolder, createSubfolders ? sourceFolderName : "");
        // create subfolders if configured so    
        boolean success = !createSubfolders || createFoldersLocally(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImagesMap.keySet()) {
            success = success && exportImageLocally(processId, image, targetFolderPath);
        }

        return success;
    }
    
    private boolean createFoldersLocally(int processId, Path folderPath) {
        try {
            // no exception will be thrown if the directories are already there, hence no need to check
            storageProvider.createDirectories(folderPath);
            return true;
        } catch (IOException e) {
            String message = "IOException caught while trying to create the directories locally under " + folderPath.toString();
            logBoth(processId, LogType.ERROR, message);
            return false;
        }
    }

    private boolean exportImageLocally(int processId, Image image, Path targetFolderPath) {
        String imageName = image.getImageName();
        //        String imageSource = image.getUrl();
        Path imageSourcePath = image.getImagePath();
        Path imageTargetPath = targetFolderPath.resolve(imageName);
        log.debug("imageName = " + imageName);
        //        log.debug("imageSource = " + imageSource);
        log.debug("imageSourcePath = " + imageSourcePath);
        log.debug("imageTargetPath = " + imageTargetPath);

        try {
            storageProvider.copyFile(imageSourcePath, imageTargetPath);
            return true;
        } catch (IOException e) {
            String message = "IOException caught while trying to copy the image named " + imageName + " locally";
            logBoth(processId, LogType.ERROR, message);
            return false;
        }
    }

    // =============== // EXPORT LOCALLY // =============== //

    private boolean exportMetsFile(Process process, Map<String, Integer> selectedImagesNamesOrderMap) {
        log.debug("exporting Mets file");
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();

            //            initializeTypes(process);

            // physical structure
            DocStruct boundBook = dd.getPhysicalDocStruct();

            MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");

            List<DocStruct> children = new ArrayList<>(boundBook.getAllChildren());
            for (DocStruct child : children) {
                String id = child.getIdentifier(); // PHYS_0023
                String imageName = child.getImageName(); // 00000023.jpg

                //                log.debug("--------------------");

                List<Reference> fromReferences = new ArrayList<>(child.getAllFromReferences());
                //                log.debug("fromReferences has size = " + fromReferences.size()); // 2
                for (Reference reference : fromReferences) {
                    String refType = reference.getType(); // logical_physical, logical_physical
                    DocStruct source = reference.getSource();
                    DocStruct target = reference.getTarget();

                    String sourceId = source.getIdentifier(); // LOG_0000, LOG_0006
                    String sourceImageName = source.getImageName(); // null, null

                    String targetId = target.getIdentifier(); // PHYS_0023, PHYS_0023
                    String targetImageName = target.getImageName(); // 00000023.jpg, 00000023.jpg

                    if (!selectedImagesNamesOrderMap.containsKey(targetImageName)) {
                        source.removeReferenceTo(target);
                        target.removeReferenceFrom(source);
                    }
                }

                List<ContentFileReference> contentFileReferences = child.getAllContentFileReferences();
                //                log.debug("contentFileReferences has size = " + contentFileReferences.size()); // 1
                for (ContentFileReference reference : contentFileReferences) {
                    ContentFile cf = reference.getCf();
                    String cfId = cf.getIdentifier(); // FILE_0023
                }

                if (selectedImagesNamesOrderMap.containsKey(imageName)) {
                    // update physical order of the image page
                    Metadata physPage = child.getAllMetadataByType(typePhysPage).get(0);
                    physPage.setValue(selectedImagesNamesOrderMap.get(imageName).toString());
                } else {
                    boundBook.removeChild(child);
                }

            }


            // logical structure
            DocStruct logical = dd.getLogicalDocStruct();

            List<Metadata> logicalMetadataList = logical.getAllMetadata();
            log.debug("logicalMetadataList {}", logicalMetadataList == null ? "is null" : " has " + logicalMetadataList.size() + " elements"); // 23
            for (Metadata md : logicalMetadataList) {
                String mdTypeName = md.getType().getName();
                log.debug("logical has Metadata of type: " + mdTypeName);
            }

            List<MetadataType> mdTypes = logical.getDefaultDisplayMetadataTypes();
            log.debug("mdTypes {}", mdTypes == null ? "is null" : " has " + mdTypes.size() + " elements"); // 2
            for (MetadataType mdt : mdTypes) {
                String mdTypeName = mdt.getName();
                log.debug("logical has MetadataType: " + mdTypeName);
            }

            List<DocStruct> logicalChildren = logical.getAllChildren();
            for (DocStruct child : logicalChildren) {
                String id = child.getIdentifier(); // LOG_0006
                DocStructType dsType = child.getType();
                String dsTypeName = dsType.getName(); // Figure

                log.debug("--------------------");
                log.debug("id = " + id);
                log.debug("dsTypeName = " + dsTypeName);

            }
            
            // file set
            FileSet fileSet = dd.getFileSet();
            List<ContentFile> contentFiles = new ArrayList<>(fileSet.getAllFiles());
            for (ContentFile file : contentFiles) {
                boolean shouldRemove = false;
                List<DocStruct> referenced = file.getReferencedDocStructs();
                //                log.debug("referenced has size = " + referenced.size()); // 1
                for (DocStruct ds : referenced) {
                    String referencedImageName = ds.getImageName(); // 00000023.jpg
                    String referencedId = ds.getIdentifier(); // PHYS_0023

                    if (!selectedImagesNamesOrderMap.containsKey(referencedImageName)) {
                        //                        contentFiles.remove(ds);
                        shouldRemove = true;
                    }
                }
                if (shouldRemove) {
                    fileSet.removeFile(file);
                }
            }
            
            process.saveTemporaryMetsFile(ff);

            return true;

        } catch (ReadException | IOException | SwapException | PreferencesException | WriteException e) {
            String message = "Errors happened trying to export the Mets file.";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * 
     * @param process
     * @return SubnodeConfiguration object according to the project's name
     */
    private SubnodeConfiguration getConfig(Process process) {
        String projectName = process.getProjekt().getTitel();
        log.debug("projectName = " + projectName);
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        SubnodeConfiguration conf = null;

        // order of configuration is:
        // 1.) project name matches
        // 2.) project is *
        try {
            conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "']");
        } catch (IllegalArgumentException e) {
            conf = xmlConfig.configurationAt("//config[./project = '*']");
        }

        return conf;
    }

    /**
     * 
     * @param processId
     * @param logType
     * @param message message to be shown to both terminal and journal
     */
    private void logBoth(int processId, LogType logType, String message) {
        String logMessage = "Selected Images Export Plugin: " + message;
        switch (logType) {
            case ERROR:
                log.error(logMessage);
                break;
            case DEBUG:
                log.debug(logMessage);
                break;
            case WARN:
                log.warn(logMessage);
                break;
            default: // INFO
                log.info(logMessage);
                break;
        }
        if (processId > 0) {
            Helper.addMessageToProcessJournal(processId, logType, logMessage);
        }
    }
}
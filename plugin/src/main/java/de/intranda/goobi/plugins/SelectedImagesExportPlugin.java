package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
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
        List<String> selectedImagesNames = getSelectedImagesNames(process);
        for (String name : selectedImagesNames) {
            log.debug("name = " + name);
        }

        List<Image> selectedImages = getSelectedImages(process, selectedImagesNames);
        boolean success = selectedImages != null;
        for (Image image : selectedImages) {
            log.debug("image.getImageName() = " + image.getImageName());
            log.debug("image.getImagePath() = " + image.getImagePath());
        }

        // export the selected images
        success = success && exportSelectedImages(process, selectedImages);

        // export the mets-file
        success = success && (!exportMetsFile || exportMetsFile(process, selectedImages));

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

    private List<String> getSelectedImagesNames(Process process) {
        List<String> selectedImagesNames = new ArrayList<>();
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
                selectedImagesNames.add(reducedImageName);
            }
        }

        return selectedImagesNames;
    }

    private List<Image> getSelectedImages(Process process, List<String> imageNames) throws IOException, SwapException, DAOException {
        log.debug("getting selected images");
        String imageFolder = process.getConfiguredImageFolder(sourceFolderName);
        if (StringUtils.isBlank(imageFolder)) {
            log.debug("The folder configured as '" + sourceFolderName + "' does not exist yet.");
            return null;
        }

        List<Image> selectedImages = new ArrayList<>();

        Path imageFolderPath = Path.of(imageFolder);
        log.debug("imageFolderPath = " + imageFolderPath);
        List<Path> imagePaths = storageProvider.listFiles(imageFolder);
        log.debug("imagePaths has size " + imagePaths.size());

        Integer thumbnailSize = null;
        int order = 0;
        for (String imageName : imageNames) {
            for (Path imagePath : imagePaths) {
                String fileName = imagePath.getFileName().toString();
                if (imageName.equals(fileName)) {
                    Image image = new Image(process, imageFolder, fileName, order++, thumbnailSize);
                    selectedImages.add(image);
                }
            }
        }

        return selectedImages;
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

    private boolean exportSelectedImages(Process process, List<Image> selectedImages) {
        int processId = process.getId();
        return useScp ? exportSelectedImagesUsingScp(processId, selectedImages) : exportSelectedImagesLocally(processId, selectedImages);
    }

    // ================= EXPORT USING SCP ================= // 
    private boolean exportSelectedImagesUsingScp(int processId, List<Image> selectedImages) {
        Path targetFolderPath = Path.of(targetFolder);
        // create subfolders if configured so
        boolean success = !createSubfolders || createSubfoldersUsingScp(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImages) {
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
    private boolean exportSelectedImagesLocally(int processId, List<Image> selectedImages) {
        Path targetFolderPath = Path.of(targetFolder, createSubfolders ? sourceFolderName : "");
        // create subfolders if configured so    
        boolean success = !createSubfolders || createFoldersLocally(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImages) {
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

    private boolean exportMetsFile(Process process, List<Image> selectedImages) {

        return true;
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
        String logMessage = "VLM Export Plugin: " + message;
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
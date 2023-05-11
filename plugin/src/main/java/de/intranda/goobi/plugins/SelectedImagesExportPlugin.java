package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

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
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
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

    // whether or not to export a JSON file
    private boolean exportJsonFile;
    // whether or not to export a METS file
    private boolean exportMetsFile;
    // whether or not to create subfolders under target folder
    private boolean createSubfolders;
    // name of the Processproperty that holds information of all selected images
    private String propertyName;
    // media | master | ...
    private String sourceFolderName;
    // target folder for the export
    private String targetFolder;

    // whether or not to use scp for the export
    private boolean useScp;
    // path to the known_hosts file, which by default should be {user.home}/.ssh/known_hosts
    private String knownHosts;
    // user name to use scp for the export
    private String scpLogin;
    // password to use scp for the export
    private String scpPassword;
    // host name to use scp for the export
    private String scpHostname;

    // path to the targeted folder for the export
    private Path targetFolderPath;

    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String TEMP_FILE_NAME = "temp.xml";
    private static final String METS_FILE_NAME = "mets.xml";
    private static final String JSON_FILE_NAME = "selected.json";

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

        // read mets file to test if it is readable
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = null;
            ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();
            VariableReplacer replacer = new VariableReplacer(dd, prefs, process, null);

            // read information from config file
            initializeFields(process, replacer);

        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            problems.add("Cannot read metadata file.");
            return false;
        }

        // get the maps of selected images
        Map<String, Integer> selectedImagesNamesOrderMap = getSelectedImagesNamesOrderMap(process);
        Map<Image, Integer> selectedImagesOrderMap = getSelectedImagesOrderMap(process, selectedImagesNamesOrderMap);

        boolean success = selectedImagesOrderMap != null;

        // export the selected images
        success = success && exportSelectedImages(process, selectedImagesOrderMap);

        // export the JSON-file
        success = success && (!exportJsonFile || exportJsonFile(process, selectedImagesOrderMap));

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

    /**
     * initialize private fields
     * 
     * @param process Goobi process
     * @param replacer VariableReplacer
     */
    private void initializeFields(Process process, VariableReplacer replacer) {
        SubnodeConfiguration config = getConfig(process);
        exportJsonFile = config.getBoolean("./exportJSON", false);
        exportMetsFile = config.getBoolean("./exportMetsFile", false);
        createSubfolders = config.getBoolean("./createSubfolders", false);
        propertyName = config.getString("./propertyName", "").trim();
        sourceFolderName = config.getString("./sourceFolder", "").trim();
        targetFolder = config.getString("targetFolder", "").trim();

        useScp = config.getBoolean("./useScp", false);
        knownHosts = config.getString("knownHosts", "").trim();
        scpLogin = config.getString("./scpLogin", "");
        scpPassword = config.getString("./scpPassword", "");
        scpHostname = config.getString("./scpHostname", "").trim();

        // apply variable replacer on certain fields
        propertyName = replacer.replace(propertyName);
        targetFolder = replacer.replace(targetFolder);

        // create subfolders if configured so
        targetFolderPath = Path.of(targetFolder, createSubfolders ? sourceFolderName : "");

        log.debug("exportJSON: {}", exportJsonFile ? "yes" : "no");
        log.debug("exportMetsFile: {}", exportMetsFile ? "yes" : "no");
        log.debug("createSubfolders: {}", createSubfolders ? "yes" : "no");
        log.debug("propertyName = " + propertyName);
        log.debug("sourceFolderName = " + sourceFolderName);
        log.debug("targetFolder = " + targetFolder);
        log.debug("useScp: {}", useScp ? "yes" : "no");
    }

    /**
     * get a map between selected Image objects and their orders among all selected
     * 
     * @param process Goobi process
     * @param imageNamesOrderMap map between names of selected images and their orders among all selected
     * @return a map between selected Image objects and their orders among all selected
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
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

    /**
     * get the map between names of selected images and their orders among all selected
     * 
     * @param process Goobi process
     * @return the map between names of selected images and their orders among all selected
     */
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

    /**
     * get the proper Processproperty object holding information of all selected images
     * 
     * @param process Goobi process
     * @return the Processproperty object holding information of all selected images
     */
    private Processproperty getProcessproperty(Process process) {
        List<Processproperty> props = PropertyManager.getProcessPropertiesForProcess(process.getId());
        for (Processproperty p : props) {
            if (propertyName.equals(p.getTitel())) {
                return p;
            }
        }
        // nothing found, report it
        String message = "Can not find a proper process property. Please recheck your configuration.";
        logBoth(process.getId(), LogType.INFO, message);
        return null;
    }

    /**
     * export all selected images
     * 
     * @param process Goobi process
     * @param selectedImagesOrderMap map between selected Image objects and their orders among all selected
     * @return true if all selected images are successfully exported, false otherwise
     */
    private boolean exportSelectedImages(Process process, Map<Image, Integer> selectedImagesOrderMap) {
        int processId = process.getId();

        return useScp ? exportSelectedImagesUsingScp(processId, selectedImagesOrderMap)
                : exportSelectedImagesLocally(processId, selectedImagesOrderMap);
    }

    // ================= EXPORT USING SCP ================= // 
    /**
     * export all selected images via scp
     * 
     * @param processId id of the Goobi process
     * @param selectedImagesOrderMap map between selected Image objects and their orders among all selected
     * @return true if all selected images are successfully exported via scp, false otherwise
     */
    private boolean exportSelectedImagesUsingScp(int processId, Map<Image, Integer> selectedImagesOrderMap) {
        // check all necessary fields
        boolean success = checkFieldsForScp(processId);

        // create folders if necessary
        success = success && createFoldersUsingScp(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImagesOrderMap.keySet()) {
            success = success && exportImageUsingScp(processId, image, targetFolderPath);
        }

        return success;
    }

    /**
     * create folders via scp
     * 
     * @param processId id of the Goobi process
     * @param folderPath path to the folder that should be created
     * @return true if the folder is successfully created, false if any JSchException should happen
     */
    private boolean createFoldersUsingScp(int processId, Path folderPath) {
        ChannelExec channelExec = getChannelExec(processId);
        if (channelExec == null) {
            return false;
        }

        String command = "mkdir -p " + folderPath;
        channelExec.setCommand(command);

        try {
            channelExec.connect();

        } catch (JSchException e) {
            String message = "Failed to create subfolders remotely.";
            logBoth(processId, LogType.ERROR, message);
            return false;

        } finally {
            channelExec.disconnect();
        }

        return true;
    }

    /**
     * export the image via scp
     * 
     * @param processId id of the Goobi process
     * @param image Image object that should be exported
     * @param targetFolderPath path to the targeted folder
     * @return true if the image is successfully exported via scp, false otherwise
     */
    private boolean exportImageUsingScp(int processId, Image image, Path targetFolderPath) {
        log.debug("exporting image using scp");

        String imageName = image.getImageName();
        String imageSourcePath = image.getImagePath().toString();
        String imageTargetPath = targetFolderPath.resolve(imageName).toString();

        return exportFileUsingScp(processId, imageName, imageSourcePath, imageTargetPath);
    }

    /**
     * validate all necessary fields for the export via scp
     * 
     * @param processId id of the Goobi process
     * @return true if all necessary fields for the export via scp are valid, false otherwise
     */
    private boolean checkFieldsForScp(int processId) {
        String message = "";
        if (StringUtils.isBlank(knownHosts)) {
            knownHosts = System.getProperty("user.home").concat("/.ssh/known_hosts");
        }
        if (StringUtils.isBlank(scpLogin)) {
            message += "scpLogin should not be blank. ";
        }
        if (StringUtils.isBlank(scpPassword)) {
            message += "scpPassword should not be blank. ";
        }
        if (StringUtils.isBlank(scpHostname)) {
            message += "scpHostname should not be blank. ";
        }

        if (StringUtils.isNotBlank(message)) {
            logBoth(processId, LogType.ERROR, message);
            return false;
        }

        return true;
    }
    // =============== // EXPORT USING SCP // =============== //

    // ================= EXPORT LOCALLY ================= // 
    /**
     * export all selected images locally
     * 
     * @param processId id of the Goobi process
     * @param selectedImagesMap map between selected Image objects and their orders among all selected
     * @return true if all selected images are successfully exported, false otherwise
     */
    private boolean exportSelectedImagesLocally(int processId, Map<Image, Integer> selectedImagesOrderMap) {
        // create folders if necessary
        boolean success = createFoldersLocally(processId, targetFolderPath);

        // copy all selected images to targetFolderPath
        for (Image image : selectedImagesOrderMap.keySet()) {
            success = success && exportImageLocally(processId, image, targetFolderPath);
        }

        return success;
    }
    
    /**
     * create folders locally
     * 
     * @param processId id of the Goobi process
     * @param folderPath path to the folder that should be created
     * @return true if the folder is successfully created, false if any IOException should happen
     */
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

    /**
     * export the image locally
     * 
     * @param processId id of the Goobi process
     * @param image Image object that is to be exported
     * @param targetFolderPath path to the targeted folder
     * @return true if the image is successfully exported, false otherwise
     */
    private boolean exportImageLocally(int processId, Image image, Path targetFolderPath) {
        String imageName = image.getImageName();
        Path imageSourcePath = image.getImagePath();
        Path imageTargetPath = targetFolderPath.resolve(imageName);
        log.debug("imageName = " + imageName);
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

    // =============== GENERATE AND EXPORT JSON FILE =============== //
    /**
     * generate and export a JSON file
     * 
     * @param process Goobi process
     * @param selectedImagesOrderMap map between selected Image objects and their orders among all selected
     * @return true if the expected JSON file is successfully generated and exported, false otherwise
     */
    private boolean exportJsonFile(Process process, Map<Image, Integer> selectedImagesOrderMap) {
        boolean jsonFileGenerated = generateJsonFile(process, selectedImagesOrderMap);
        if (!jsonFileGenerated) {
            return false;
        }

        try {
            String processDataDirectory = process.getProcessDataDirectory();
            Path sourcePath = Path.of(processDataDirectory, JSON_FILE_NAME);
            log.debug("sourcePath = " + sourcePath);

            Path targetPath = targetFolderPath.resolve(JSON_FILE_NAME);
            log.debug("targetPath = " + targetPath);

            if (useScp) {
                return exportFileUsingScp(process.getId(), JSON_FILE_NAME, sourcePath.toString(), targetPath.toString());
            }

            // otherwise, export locally
            storageProvider.copyFile(sourcePath, targetPath);
            return true;

        } catch (IOException | SwapException e) {
            String message = "Exceptions happened while trying to export the Mets file via scp.";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * generate a temporary JSON file in preparation for the export
     * 
     * @param process Goobi process
     * @param selectedImagesOrderMap map between selected Image objects and their orders among all selected
     * @return true if the temporary JSON file is successfully generated, false otherwise
     */
    private boolean generateJsonFile(Process process, Map<Image, Integer> selectedImagesOrderMap) {
        // generate JSON string
        String jsonString = generateJsonString(process, selectedImagesOrderMap);
        log.debug(jsonString);

        // save the generated JSON string into a file
        try {
            String processDataDirectory = process.getProcessDataDirectory();
            Path jsonFilePath = Path.of(processDataDirectory, JSON_FILE_NAME);
            log.debug("jsonFilePath = " + jsonFilePath);

            if (!storageProvider.isFileExists(jsonFilePath)) {
                storageProvider.createFile(jsonFilePath);
            }

            try (OutputStream out = storageProvider.newOutputStream(jsonFilePath)) {
                out.write(jsonString.getBytes());
                out.flush();
            }

        } catch (IOException | SwapException e) {
            String message = "Failed to generate a JSON file. Aborting.";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }

        return true;
    }

    /**
     * generate the JSON string that should be saved to a file
     * 
     * @param process Goobi process
     * @param selectedImagesOrderMap map between selected Image objects and their orders among all selected
     * @return generate the JSON string that should be saved to a file
     */
    private String generateJsonString(Process process, Map<Image, Integer> selectedImagesOrderMap) {
        updateJsonPropertyNamesFromConfig(process);
        SelectedImages images = new SelectedImages(selectedImagesOrderMap.size());
        // TODO: there must be a way to retrieve or generate HERIS-ID
        images.setHerisId(34);

        for (Image image : selectedImagesOrderMap.keySet()) {
            // 00000018.jpg
            String imageName = image.getImageName();
            // /opt/digiverso/goobi/metadata/4/images/thunspec_577843346_media/00000018.jpg
            Path imagePath = image.getImagePath();
            // jpeg
            String largeImageFormat = image.getLargeImageFormat();
            // 00000018.jpg
            String tooltip = image.getTooltip();

            SelectedImageProperties imageProperties = new SelectedImageProperties();
            // TODO: what should be used as id for an image? 
            imageProperties.setId("15700682");
            imageProperties.setTitle(imageName);
            imageProperties.setAltText(tooltip);
            imageProperties.setSymbol(true);
            imageProperties.setMediaType(largeImageFormat);
            imageProperties.setCopyrightBDA(true);
            imageProperties.setFileInformation(tooltip);
            imageProperties.setPublishable(true);
            imageProperties.setMigratedInformation(null);

            String fileCreationTime = storageProvider.getFileCreationTime(imagePath); // 2022-11-01T09:19:56Z
            String fileCreationDate = fileCreationTime.substring(0, fileCreationTime.indexOf("T"));
            imageProperties.setCreationDate(fileCreationDate);

            images.addImage(imageProperties, selectedImagesOrderMap.get(image) - 1); // list index starting from 0
        }

        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(SelectedImages.class, new SelectedImagesSerializer());
        gsonBuilder.setPrettyPrinting();
        final Gson gson = gsonBuilder.serializeNulls().create();

        String result = gson.toJson(images).replace("\\n", "\n");

        return result.replace("\"[", "[").replace("]\"", "]").replace("\\\"", "\"");
    }

    /**
     * update the names of properties that should be used in the JSON file
     * 
     * @param process Goobi process
     */
    private void updateJsonPropertyNamesFromConfig(Process process) {
        // get configured names for JSON from the config
        SubnodeConfiguration jsonConfig = getJsonConfig(process);

        // update field names for SelectedImagesSerializer
        String images = jsonConfig.getString("./images");
        SelectedImagesSerializer.setImages(images);

        String herisId = jsonConfig.getString("herisId");
        SelectedImagesSerializer.setHerisId(herisId);

        // update field names for SelectedImagePropertiesSerializer
        String idName = jsonConfig.getString("./idName", "");
        SelectedImagePropertiesSerializer.setId(idName);

        String title = jsonConfig.getString("./title", "");
        SelectedImagePropertiesSerializer.setTitle(title);

        String altText = jsonConfig.getString("./altText", "");
        SelectedImagePropertiesSerializer.setAltText(altText);

        String symbolImage = jsonConfig.getString("./symbolImage", "");
        SelectedImagePropertiesSerializer.setSymbolImage(symbolImage);

        String mediaType = jsonConfig.getString("./mediaType", "");
        SelectedImagePropertiesSerializer.setMediaType(mediaType);

        String creationDate = jsonConfig.getString("./creationDate", "");
        SelectedImagePropertiesSerializer.setCreationDate(creationDate);

        String copyRightBDA = jsonConfig.getString("./copyRightBDA", "");
        SelectedImagePropertiesSerializer.setCopyrightBDA(copyRightBDA);

        String fileInformation = jsonConfig.getString("./fileInformation", "");
        SelectedImagePropertiesSerializer.setFileInformation(fileInformation);

        String publishable = jsonConfig.getString("./publishable", "");
        SelectedImagePropertiesSerializer.setPublishable(publishable);

        String migratedInformation = jsonConfig.getString("./migratedInformation", "");
        SelectedImagePropertiesSerializer.setMigratedInformation(migratedInformation);
    }
    // =============== // GENERATE AND EXPORT JSON FILE // =============== //

    // =============== GENERATE AND EXPORT METS FILE =============== //
    /**
     * generate and export the mets file
     * 
     * @param process Goobi process
     * @param selectedImagesNamesOrderMap map between names of selected images and their orders among all selected
     * @return true if the mets file is successfully exported, false otherwise
     */
    private boolean exportMetsFile(Process process, Map<String, Integer> selectedImagesNamesOrderMap) {
        // folders should already be created while trying to copy the image files, hence no need to create them again
        boolean metsFileGenerated = generateMetsFile(process, selectedImagesNamesOrderMap);
        if (!metsFileGenerated) {
            return false;
        }

        try {
            String processDataDirectory = process.getProcessDataDirectory();
            Path sourcePath = Path.of(processDataDirectory, TEMP_FILE_NAME);
            log.debug("sourcePath = " + sourcePath);

            Path targetPath = targetFolderPath.resolve(METS_FILE_NAME);
            log.debug("targetPath = " + targetPath);
            
            if (useScp) {
                return exportFileUsingScp(process.getId(), TEMP_FILE_NAME, sourcePath.toString(), targetPath.toString());
            }
            
            // otherwise, export locally
            storageProvider.copyFile(sourcePath, targetPath);
            return true;
            
        } catch (IOException | SwapException e) {
            String message = "Exceptions happened while trying to export the Mets file via scp.";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * generate a temporary mets file in preparation for the export
     * 
     * @param process Goobi process
     * @param selectedImagesNamesOrderMap map between names of selected images and their orders among all selected
     * @return true if a temporary mets file is successfully generated, false otherwise
     */
    private boolean generateMetsFile(Process process, Map<String, Integer> selectedImagesNamesOrderMap) {
        log.debug("generating Mets file");
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            Fileformat ff = process.readMetadataFile();
            DigitalDocument dd = ff.getDigitalDocument();

            // physical structure
            processPhysicalStructure(prefs, dd, selectedImagesNamesOrderMap);
            
            // file set
            processFileSet(dd, selectedImagesNamesOrderMap);

            // generate temporary mets file
            process.saveTemporaryMetsFile(ff);

            return true;

        } catch (ReadException | IOException | SwapException | PreferencesException | WriteException e) {
            String message = "Errors happened trying to generate the Mets file.";
            logBoth(process.getId(), LogType.ERROR, message);
            return false;
        }
    }

    /**
     * filter out information of unselected images from the physical structure
     * 
     * @param prefs
     * @param dd DigitalDocument
     * @param selectedImagesNamesOrderMap map between names of selected images and their orders among all selected
     */
    private void processPhysicalStructure(Prefs prefs, DigitalDocument dd, Map<String, Integer> selectedImagesNamesOrderMap) {
        DocStruct physical = dd.getPhysicalDocStruct();

        MetadataType typePhysPage = prefs.getMetadataTypeByName("physPageNumber");

        List<DocStruct> children = new ArrayList<>(physical.getAllChildren());
        for (DocStruct child : children) {
            String imageName = child.getImageName(); // 00000023.jpg

            List<Reference> fromReferences = new ArrayList<>(child.getAllFromReferences());
            for (Reference reference : fromReferences) {
                DocStruct source = reference.getSource();
                DocStruct target = reference.getTarget();
                String targetImageName = target.getImageName(); // 00000023.jpg, 00000023.jpg

                if (!selectedImagesNamesOrderMap.containsKey(targetImageName)) {
                    source.removeReferenceTo(target);
                    target.removeReferenceFrom(source);
                }
            }

            if (selectedImagesNamesOrderMap.containsKey(imageName)) {
                // update physical order of the image page
                Metadata physPage = child.getAllMetadataByType(typePhysPage).get(0);
                physPage.setValue(selectedImagesNamesOrderMap.get(imageName).toString());
            } else {
                physical.removeChild(child);
            }
        }
    }

    /**
     * filter out information of unselected images from the file set
     * 
     * @param dd DigitalDocument
     * @param selectedImagesNamesOrderMap map between selected Image objects and their orders among all selected
     */
    private void processFileSet(DigitalDocument dd, Map<String, Integer> selectedImagesNamesOrderMap) {
        FileSet fileSet = dd.getFileSet();
        List<ContentFile> contentFiles = new ArrayList<>(fileSet.getAllFiles());
        for (ContentFile file : contentFiles) {
            boolean shouldRemove = false;
            List<DocStruct> referenced = file.getReferencedDocStructs();
            for (DocStruct ds : referenced) {
                String referencedImageName = ds.getImageName(); // 00000023.jpg
                if (!selectedImagesNamesOrderMap.containsKey(referencedImageName)) {
                    shouldRemove = true;
                }
            }
            if (shouldRemove) {
                fileSet.removeFile(file);
            }
        }
    }

    // =============== // GENERATE AND EXPORT METS FILE // =============== //
    /**
     * export a file via scp
     * 
     * @param processId id of the Goobi process
     * @param fileName name of the file that should be exported via scp
     * @param sourcePath source path of the to be exported file
     * @param targetPath target path of the to be exported file
     * @return true if the export succeeds, false otherwise
     */
    private boolean exportFileUsingScp(int processId, String fileName, String sourcePath, String targetPath) {
        ChannelExec channelExec = getChannelExec(processId);
        if (channelExec == null) {
            return false;
        }

        targetPath = targetPath.replace("'", "'\"'\"'");
        targetPath = "'" + targetPath + "'";

        String command = "scp " + " -t " + targetPath;
        log.debug("command = " + command);
        channelExec.setCommand(command);

        try (OutputStream out = channelExec.getOutputStream();
                InputStream in = channelExec.getInputStream()) {

            channelExec.connect();
            log.debug("channel connected, starting to export");

            String ackFailureMessage = "Ack check failed while trying to export file using scp. Aborting.";
            if (checkAck(in) != 0) {
                logBoth(processId, LogType.ERROR, ackFailureMessage);
                return false;
            }

            // send "C0644 fileSize fileName", where fileName should not include '/'
            File file = new File(sourcePath);
            long fileSize = file.length();
            command = "C0644 " + fileSize + " " + fileName + "\n";
            out.write(command.getBytes());
            out.flush();
            if (checkAck(in) != 0) {
                logBoth(processId, LogType.ERROR, ackFailureMessage);
                return false;
            }

            // send content of file
            byte[] buf = new byte[1024];
            try (FileInputStream fis = new FileInputStream(file)) {
                while (true) {
                    int len = fis.read(buf, 0, buf.length);
                    if (len <= 0) {
                        break;
                    }
                    out.write(buf, 0, len);
                }
            }

            // send '\0'
            buf[0] = 0;
            out.write(buf, 0, 1);
            out.flush();
            if (checkAck(in) != 0) {
                logBoth(processId, LogType.ERROR, ackFailureMessage);
                return false;
            }

        } catch (JSchException | IOException e) {
            String message = "Failed to export image '" + fileName + "'";
            logBoth(processId, LogType.ERROR, message);
            return false;

        } finally {
            channelExec.disconnect();
        }

        return true;
    }

    /**
     * get a ChannelExec to perform an export via scp
     * 
     * @param processId id of the Goobi process
     * @return ChannelExec object
     */
    private ChannelExec getChannelExec(int processId) {
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(knownHosts);
            Session jschSession = jsch.getSession(scpLogin, scpHostname);
            jschSession.setPassword(scpPassword);
            jschSession.connect();

            return (ChannelExec) jschSession.openChannel("exec");

        } catch (JSchException e) {
            String message = "Failed to set up Jsch.";
            logBoth(processId, LogType.ERROR, message);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * get the XML configuration of this plugin
     * 
     * @return the XMLConfiguration of this plugin
     */
    private XMLConfiguration getXMLConfig() {
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        return xmlConfig;
    }

    /**
     * get the SubnodeConfiguration of the current process
     * 
     * @param process Goobi process
     * @return SubnodeConfiguration object according to the project's name
     */
    private SubnodeConfiguration getConfig(Process process) {
        String projectName = process.getProjekt().getTitel();
        log.debug("projectName = " + projectName);
        XMLConfiguration xmlConfig = getXMLConfig();
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
     * get the SubnodeConfiguration of json_format block
     * 
     * @param process Goobi process
     * @return SubnodeConfiguration of json_format block
     */
    private SubnodeConfiguration getJsonConfig(Process process) {
        SubnodeConfiguration config = getConfig(process);
        SubnodeConfiguration jsonConfig = null;
        try {
            jsonConfig = config.configurationAt("./json_format");
        } catch (IllegalArgumentException e) {
            jsonConfig = getXMLConfig().configurationAt("//json_format");
        }

        return jsonConfig;
    }

    /**
     * write log message into both terminal and Journal
     * 
     * @param processId id of the Goobi process
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

    /**
     * check the ack value returned by the server
     * 
     * @param in InputStream
     * @return response from the server
     * @throws IOException
     */
    private static int checkAck(InputStream in) throws IOException {
        // To every command sent by the client, the server responds with a single-byte "ack", where:
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1

        if (b == 1 || b == 2) {
            StringBuilder sb = new StringBuilder();
            int c;
            do {
                c = in.read();
                sb.append((char) c);
            } while (c != '\n');

            String message = "";
            if (b == 1) { // error
                message = "Error happened trying to export file using scp: " + sb.toString();
            }
            if (b == 2) { // fatal error
                message = "Fatal error happened trying to export file using scp: " + sb.toString();
            }
            if (StringUtils.isNotBlank(message)) {
                log.error(message);
            }
        }

        return b;
    }
}
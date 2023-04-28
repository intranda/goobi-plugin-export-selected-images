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
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.export.dms.ExportDms;
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
        for (Image image : selectedImages) {
            log.debug("image.getImageName() = " + image.getImageName());
        }

        // export the selected images

        // export the mets-file

        // do a regular export here
        IExportPlugin export = new ExportDms();
        export.setExportFulltext(true);
        export.setExportImages(true);

        // execute the export and check the success
        boolean success = export.startExport(process);
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
        sourceFolderName = config.getString("./sourceFolder");
        targetFolder = config.getString("targetFolder");
        log.debug("exportMetsFile: {}", exportMetsFile ? "yes" : "no");
        log.debug("createSubfolders: {}", createSubfolders ? "yes" : "no");
        log.debug("propertyName = " + propertyName);
        log.debug("sourceFolderName = " + sourceFolderName);
        log.debug("targetFolder = " + targetFolder);
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
}
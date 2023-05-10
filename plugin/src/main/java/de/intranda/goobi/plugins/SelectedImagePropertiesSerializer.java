package de.intranda.goobi.plugins;

import java.lang.reflect.Type;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class SelectedImagePropertiesSerializer implements JsonSerializer<SelectedImageProperties> {

    private static String id = "Id";
    private static String title = "Titel";
    private static String altText = "alt_text";
    private static String symbolImage = "SymbolBild";
    private static String mediaType = "media_type";
    private static String creationDate = "Aufnahmedatum";
    private static String copyrightBDA = "Copyright BDA";
    private static String fileInformation = "Dateiinformation";
    private static String publishable = "publikationsfähig";
    private static String migratedInformation = "Migrierte Information";

    @Override
    public JsonElement serialize(SelectedImageProperties src, Type typeOfSrc, JsonSerializationContext context) {
        final JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(id, src.getId());
        jsonObject.addProperty(title, src.getTitle());
        jsonObject.addProperty(altText, src.getAltText());
        jsonObject.addProperty(symbolImage, src.isSymbol());
        jsonObject.addProperty(mediaType, src.getMediaType());
        jsonObject.addProperty(creationDate, src.getCreationDate());
        jsonObject.addProperty(copyrightBDA, src.isCopyrightBDA() ? "ja" : "nein");
        jsonObject.addProperty(fileInformation, src.getFileInformation());
        jsonObject.addProperty(publishable, src.isPublishable() ? "ja" : "nein");
        jsonObject.addProperty(migratedInformation, src.getMigratedInformation());

        return jsonObject;
    }

    public static void setId(String id) {
        if (StringUtils.isNotBlank(id)) {
            SelectedImagePropertiesSerializer.id = id;
        }
    }

    public static void setTitle(String title) {
        if (StringUtils.isNotBlank(title)) {
            SelectedImagePropertiesSerializer.title = title;
        }
    }

    public static void setAltText(String altText) {
        if (StringUtils.isNotBlank(altText)) {
            SelectedImagePropertiesSerializer.altText = altText;
        }
    }

    public static void setSymbolImage(String symbolImage) {
        if (StringUtils.isNotBlank(symbolImage)) {
            SelectedImagePropertiesSerializer.symbolImage = symbolImage;
        }
    }

    public static void setMediaType(String mediaType) {
        if (StringUtils.isNotBlank(mediaType)) {
            SelectedImagePropertiesSerializer.mediaType = mediaType;
        }
    }

    public static void setCreationDate(String creationDate) {
        if (StringUtils.isNotBlank(creationDate)) {
            SelectedImagePropertiesSerializer.creationDate = creationDate;
        }
    }

    public static void setCopyrightBDA(String copyrightBDA) {
        if (StringUtils.isNotBlank(copyrightBDA)) {
            SelectedImagePropertiesSerializer.copyrightBDA = copyrightBDA;
        }
    }

    public static void setFileInformation(String fileInformation) {
        if (StringUtils.isNotBlank(fileInformation)) {
            SelectedImagePropertiesSerializer.fileInformation = fileInformation;
        }
    }

    public static void setPublishable(String publishable) {
        if (StringUtils.isNotBlank(publishable)) {
            SelectedImagePropertiesSerializer.publishable = publishable;
        }
    }

    public static void setMigratedInformation(String migratedInformation) {
        if (StringUtils.isNotBlank(migratedInformation)) {
            SelectedImagePropertiesSerializer.migratedInformation = migratedInformation;
        }
    }

}

package de.intranda.goobi.plugins;

import java.lang.reflect.Type;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class SelectedImagePropertiesSerializer implements JsonSerializer<SelectedImageProperties> {

    private String id = "Id";
    private String title = "Titel";
    private String altText = "alt_text";
    private String isSymbol = "SymbolBild";
    private String mediaType = "media_type";
    private String creationDate = "Aufnahmedatum";
    private String isCopyrightBDA = "Copyright BDA";
    private String fileInformation = "Dateiinformation";
    private String isPublishable = "publikationsfähig";
    private String migratedInformation = "Migrierte Information";

    @Override
    public JsonElement serialize(SelectedImageProperties src, Type typeOfSrc, JsonSerializationContext context) {
        final JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(id, src.getId());
        jsonObject.addProperty(title, src.getTitle());
        jsonObject.addProperty(altText, src.getAltText());
        jsonObject.addProperty(isSymbol, src.isSymbol());
        jsonObject.addProperty(mediaType, src.getMediaType());
        jsonObject.addProperty(creationDate, src.getCreationDate());
        jsonObject.addProperty(isCopyrightBDA, src.isCopyrightBDA() ? "ja" : "nein");
        jsonObject.addProperty(fileInformation, src.getFileInformation());
        jsonObject.addProperty(isPublishable, src.isPublishable() ? "ja" : "nein");
        jsonObject.addProperty(migratedInformation, src.getMigratedInformation());

        return jsonObject;
    }

}

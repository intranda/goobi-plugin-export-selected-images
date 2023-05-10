package de.intranda.goobi.plugins;

import java.lang.reflect.Type;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class SelectedImagesSerializer implements JsonSerializer<SelectedImages> {

    private String images = "Bilder";
    private String herisId = "HERIS-ID";

    @Override
    public JsonElement serialize(SelectedImages src, Type typeOfSrc, JsonSerializationContext context) {
        final JsonObject jsonObject = new JsonObject();

        List<SelectedImageProperties> propertiesList = src.getImages();

        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(SelectedImageProperties.class, new SelectedImagePropertiesSerializer());
        gsonBuilder.setPrettyPrinting();
        final Gson gson = gsonBuilder.serializeNulls().create();

        StringBuilder sb = new StringBuilder("[\n");
        for (SelectedImageProperties property : propertiesList) {
            sb.append(gson.toJson(property));
            sb.append(",\n");
        }
        if (sb.length() > 2) {
            // i.e. there was at least one image selected
            sb.deleteCharAt(sb.lastIndexOf(","));
        }
        sb.append("]");

        jsonObject.addProperty(images, sb.toString());
        jsonObject.addProperty(herisId, src.getHerisId());

        return jsonObject;
    }
}

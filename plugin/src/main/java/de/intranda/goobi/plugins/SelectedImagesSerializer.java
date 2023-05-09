package de.intranda.goobi.plugins;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

public class SelectedImagesSerializer implements JsonSerializer<SelectedImages> {

    private String images = "Bilder";
    private String herisId = "HERIS-ID";

    @Override
    public JsonElement serialize(SelectedImages src, Type typeOfSrc, JsonSerializationContext context) {
        final JsonObject jsonObject = new JsonObject();

        // serializer for the list of SelectedImageProperties objects
        JsonSerializer<List<SelectedImageProperties>> listSerializer = new JsonSerializer<List<SelectedImageProperties>>() {
            @Override
            public JsonElement serialize(List<SelectedImageProperties> src, Type typeOfSrc, JsonSerializationContext context) {
                JsonArray jsonProperties = new JsonArray();
                final GsonBuilder gsonBuilder = new GsonBuilder();
                gsonBuilder.registerTypeAdapter(SelectedImageProperties.class, new SelectedImagePropertiesSerializer());
                //            gsonBuilder.setPrettyPrinting();
                final Gson gson = gsonBuilder.serializeNulls().create();
                for (SelectedImageProperties property : src) {
                    String jsonProperty = gson.toJson(property);
                    jsonProperties.add(jsonProperty);
                }

                return jsonProperties;
            }
        };

        final GsonBuilder gsonBuilder = new GsonBuilder();
        Type listOfPropertiesType = new TypeToken<ArrayList<SelectedImageProperties>>() {
        }.getType();

        gsonBuilder.registerTypeAdapter(listOfPropertiesType, listSerializer);
        //        gsonBuilder.setPrettyPrinting();
        final Gson gson = gsonBuilder.serializeNulls().create();

        jsonObject.addProperty(images, gson.toJson(src.getImages()));
        jsonObject.addProperty(herisId, src.getHerisId());

        return jsonObject;
    }

}

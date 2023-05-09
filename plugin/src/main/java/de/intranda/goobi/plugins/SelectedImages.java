package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class SelectedImages {
    private List<SelectedImageProperties> images = new ArrayList<>();
    private int herisId;

    public void addImage(SelectedImageProperties imageProperties) {
        if (imageProperties != null && !images.contains(imageProperties)) {
            images.add(imageProperties);
        }
    }
}

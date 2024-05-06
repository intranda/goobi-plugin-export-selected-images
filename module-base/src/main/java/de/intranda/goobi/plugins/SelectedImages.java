package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Data;

@Data
public class SelectedImages {
    private List<SelectedImageProperties> images;
    private int herisId;

    public SelectedImages() {
        images = new ArrayList<>();
    }

    public SelectedImages(int size) {
        images = new ArrayList<>(Collections.nCopies(size, null));
    }

    public void addImage(SelectedImageProperties imageProperties) {
        if (imageProperties != null && !images.contains(imageProperties)) {
            images.add(imageProperties);
        }
    }

    public void addImage(SelectedImageProperties imageProperties, int index) {
        if (imageProperties != null && !images.contains(imageProperties)) {
            images.set(index, imageProperties);
        }
    }
}

package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class SelectedImageProperties {
    private String id;
    private String title;
    private String altText;
    private boolean isSymbol;
    private String mediaType;
    private String creationDate;
    private boolean isCopyrightBDA;
    private String fileInformation;
    private boolean isPublishable;
    private String migratedInformation;
}

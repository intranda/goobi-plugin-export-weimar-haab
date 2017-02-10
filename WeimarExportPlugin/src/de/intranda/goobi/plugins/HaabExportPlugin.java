package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.User;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;

import ugh.dl.DocStruct;
import ugh.dl.ExportFileformat;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.FilesystemHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenHelper;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
public class HaabExportPlugin extends ExportMets implements IExportPlugin, IPlugin {

    private static final String PLUGIN_NAME = "HaabExport";
    private static final Logger logger = Logger.getLogger(HaabExportPlugin.class);
    private boolean exportWithImages = true;
    private boolean exportFulltext = true;

    @Override
    public PluginType getType() {
        return PluginType.Export;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    
    public String getDescription() {
        return PLUGIN_NAME;
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String imageDirectorySuffix = "_tif";

        myPrefs = process.getRegelsatz().getPreferences();
        //        ConfigProjects cp = new ConfigProjects(process.getProjekt().getTitel());
        String atsPpnBand = process.getTitel();

        Fileformat gdzfile;

        ExportFileformat newfile = MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz());

        File benutzerHome;

        @SuppressWarnings("unchecked")
        List<String> folderList = ConfigPlugins.getPluginConfig(this).getList("exportFolder");

        for (String folder : folderList) {
            if (folder != null && !folder.isEmpty()) {
                benutzerHome = new File(folder.trim());

                try {
                    gdzfile = process.readMetadataFile();

                    newfile.setDigitalDocument(gdzfile.getDigitalDocument());
                    gdzfile = newfile;

                } catch (Exception e) {
                    Helper.setFehlerMeldung(Helper.getTranslation("exportError") + process.getTitel(), e);
                    logger.error("Export abgebrochen, xml-LeseFehler", e);
                    return false;
                }

                DocStruct logical = gdzfile.getDigitalDocument().getLogicalDocStruct();
                if (logical.getType().isAnchor()) {
                    logical = logical.getAllChildren().get(0);
                }

                List<String> whiteList = new ArrayList<>();
                whiteList.add("CoverBackInside");
                whiteList.add("CoverForeEdgeRight");
                whiteList.add("CoverFrontInside");
                whiteList.add("CoverFrontOutside");
                whiteList.add("BuchspiegelVorne");
                whiteList.add("BuchspiegelHinten");
                whiteList.add("FrontSection");
                whiteList.add("HeadSection");
                whiteList.add("FootSection");
                whiteList.add("Spine");
                whiteList.add("Wrapper");
                whiteList.add("WrapperWithTitle");
                whiteList.add("RearCover");
                whiteList.add("Cover");

                // create cover for all docstructs
                if (logical.getAllChildren() != null && !logical.getAllChildren().isEmpty()) {
                    List<DocStruct> docstructList = new ArrayList<>();
                    for (DocStruct ds : logical.getAllChildren()) {
                        if (whiteList.contains(ds.getType().getName())) {
                            docstructList.add(ds);
                        }
                    }

                    if (!docstructList.isEmpty()) {

                        DocStruct ds = gdzfile.getDigitalDocument().createDocStruct(myPrefs.getDocStrctTypeByName("Cover"));
                        // neues docstruct einfügen
                        try {
                            logical.addChild(ds);
                            logical.moveChild(ds, 0);
                        } catch (TypeNotAllowedAsChildException e) {
                            logger.error(e);
                        }
                        List<DocStruct> pages = new ArrayList<>();
                        for (DocStruct old : docstructList) {
                            // seiten zuweisen
                            List<Reference> references = old.getAllToReferences();
                            for (Reference ref : references) {
                                DocStruct page = ref.getTarget();
                                boolean checked = false;
                                for (DocStruct selectedPage : pages) {
                                    if (selectedPage.getImageName().equals(page.getImageName())) {
                                        checked = true;
                                        break;
                                    }
                                }
                                if (!checked) {
                                    pages.add(page);
                                }
                            }

                        }
                        Collections.sort(pages, new Comparator<DocStruct>() {
                            @Override
                            public int compare(DocStruct o1, DocStruct o2) {
                                MetadataType mdt = myPrefs.getMetadataTypeByName("physPageNumber");
                                String value1 = o1.getAllMetadataByType(mdt).get(0).getValue();
                                String value2 = o2.getAllMetadataByType(mdt).get(0).getValue();
                                Integer order1 = Integer.parseInt(value1);
                                Integer order2 = Integer.parseInt(value2);
                                return order1.compareTo(order2);
                            }
                        });
                        for (DocStruct page : pages) {
                            ds.addReferenceTo(page, "logical_physical");
                        }
                        //                    // alte docstruct löschen
                        for (DocStruct old : docstructList) {
                            logical.removeChild(old);
                        }
                    }

                }

                trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());

                /*
                 * -------------------------------- Speicherort vorbereiten und downloaden --------------------------------
                 */

                /*
                 * -------------------------------- der eigentliche Download der Images --------------------------------
                 */
                try {
                    if (this.exportWithImages) {
                        imageDownload(process, benutzerHome, atsPpnBand, imageDirectorySuffix);
                        fulltextDownload(process, benutzerHome, atsPpnBand);
                    } else if (this.exportFulltext) {
                        fulltextDownload(process, benutzerHome, atsPpnBand);
                    }
                } catch (Exception e) {
                    Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), e);
                    return false;
                }

                /* Wenn METS, dann per writeMetsFile schreiben... */
                writeMetsFile(process, benutzerHome + File.separator + atsPpnBand + ".xml", gdzfile, false);

            }
        }
        return true;
    }

    /**
     * run through all metadata and children of given docstruct to trim the strings calls itself recursively
     */
    private void trimAllMetadata(DocStruct inStruct) {
        /* trimm all metadata values */
        if (inStruct.getAllMetadata() != null) {
            for (Metadata md : inStruct.getAllMetadata()) {
                if (md.getValue() != null) {
                    md.setValue(md.getValue().trim());
                }
            }
        }

        /* run through all children of docstruct */
        if (inStruct.getAllChildren() != null) {
            for (DocStruct child : inStruct.getAllChildren()) {
                trimAllMetadata(child);
            }
        }
    }

    public void fulltextDownload(Process process, File benutzerHome, String atsPpnBand) throws IOException, InterruptedException, SwapException,
            DAOException {

        // Helper help = new Helper();
        // File tifOrdner = new File(process.getImagesTifDirectory());

        // download sources
        // download sources
        Path sources = Paths.get(process.getSourceDirectory());
        if (Files.exists(sources) && !NIOFileUtils.list(process.getSourceDirectory()).isEmpty()) {
            Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + "_src");
            if (!Files.exists(destination)) {
                Files.createDirectories(destination);
            }
            List<Path> dateien = NIOFileUtils.listFiles(process.getSourceDirectory());
            for (Path dir : dateien) {
                Path meinZiel = Paths.get(destination.toString(), dir.getFileName().toString());
                Files.copy(dir, meinZiel, NIOFileUtils.STANDARD_COPY_OPTIONS);
            }
        }

        Path ocr = Paths.get(process.getOcrDirectory());
        if (Files.exists(ocr)) {

            List<Path> folder = NIOFileUtils.listFiles(process.getOcrDirectory());
            for (Path dir : folder) {

                if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
                    String suffix = dir.getFileName().toString().substring(dir.getFileName().toString().lastIndexOf("_"));
                    Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                    if (!Files.exists(destination)) {
                        Files.createDirectories(destination);
                    }
                    List<Path> files = NIOFileUtils.listFiles(dir.toString());
                    for (Path file : files) {
                        Path target = Paths.get(destination.toString(), file.getFileName().toString());
                        Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
                    }
                }
            }
        }
    }

    public void imageDownload(Process process, File benutzerHome, String atsPpnBand, final String ordnerEndung) throws IOException,
            InterruptedException, SwapException, DAOException {

        /*
         * -------------------------------- dann den Ausgangspfad ermitteln --------------------------------
         */
        File tifOrdner = new File(process.getImagesTifDirectory(true));

        /*
         * -------------------------------- jetzt die Ausgangsordner in die Zielordner kopieren --------------------------------
         */
        File zielTif = new File(benutzerHome + File.separator + atsPpnBand + ordnerEndung);
        if (tifOrdner.exists() && tifOrdner.list().length > 0) {

            /* bei Agora-Import einfach den Ordner anlegen */
            if (process.getProjekt().isUseDmsImport()) {
                if (!zielTif.exists()) {
                    zielTif.mkdir();
                }
            } else {
                /*
                 * wenn kein Agora-Import, dann den Ordner mit Benutzerberechtigung neu anlegen
                 */
                User myBenutzer = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
                try {
                    FilesystemHelper.createDirectoryForUser(zielTif.getAbsolutePath(), myBenutzer.getLogin());
                } catch (Exception e) {
                    Helper.setFehlerMeldung("Export canceled, error", "could not create destination directory");
                    logger.error("could not create destination directory", e);
                }
            }

            /* jetzt den eigentlichen Kopiervorgang */

            List<Path> files = NIOFileUtils.listFiles(process.getImagesTifDirectory(true), NIOFileUtils.DATA_FILTER);
            for (Path file : files) {
                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
                Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
            }
        }

        if (ConfigurationHelper.getInstance().isExportFilesFromOptionalMetsFileGroups()) {

            List<ProjectFileGroup> myFilegroups = process.getProjekt().getFilegroups();
            if (myFilegroups != null && myFilegroups.size() > 0) {
                for (ProjectFileGroup pfg : myFilegroups) {
                    // check if source files exists
                    if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                        Path folder = Paths.get(process.getMethodFromName(pfg.getFolder()));
                        if (folder != null && java.nio.file.Files.exists(folder) && !NIOFileUtils.list(folder.toString()).isEmpty()) {
                            List<Path> files = NIOFileUtils.listFiles(folder.toString());
                            for (Path file : files) {
                                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());

                                Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
                            }
                        }
                    }
                }
            }
        }
        Path exportFolder = Paths.get(process.getExportDirectory());
        if (Files.exists(exportFolder) && Files.isDirectory(exportFolder)) {
            List<Path> subdir = NIOFileUtils.listFiles(process.getExportDirectory());
            for (Path dir : subdir) {
                if (Files.isDirectory(dir) && !NIOFileUtils.list(dir.toString()).isEmpty()) {
                    if (!dir.getFileName().toString().matches(".+\\.\\d+")) {
                        String suffix = dir.getFileName().toString().substring(dir.getFileName().toString().lastIndexOf("_"));
                        Path destination = Paths.get(benutzerHome.toString(), atsPpnBand + suffix);
                        if (!Files.exists(destination)) {
                            Files.createDirectories(destination);
                        }
                        List<Path> files = NIOFileUtils.listFiles(dir.toString());
                        for (Path file : files) {
                            Path target = Paths.get(destination.toString(), file.getFileName().toString());
                            Files.copy(file, target, NIOFileUtils.STANDARD_COPY_OPTIONS);
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {

        return startExport(process);
    }

    @Override
    public void setExportFulltext(boolean exportFulltext) {
        this.exportFulltext = exportFulltext;

    }

    @Override
    public void setExportImages(boolean exportImages) {
        exportWithImages = exportImages;
    }

}

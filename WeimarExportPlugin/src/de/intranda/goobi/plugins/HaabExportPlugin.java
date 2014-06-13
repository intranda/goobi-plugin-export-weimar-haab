package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
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
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.FilesystemHelper;
import de.sub.goobi.helper.Helper;
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

    @Override
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

        // TODO liste aktualisieren
        List<String> whiteList = new ArrayList<>();
        whiteList.add("CoverBackInside");
        whiteList.add("CoverForeEdgeRight");
        whiteList.add("CoverFrontInside");
        whiteList.add("CoverFrontOutside");
        whiteList.add("Cover");

        // test for 'Einband' and 'Buchschnitte;
        if (logical.getAllChildren() != null && !logical.getAllChildren().isEmpty()) {
            List<DocStruct> docstructList = new ArrayList<>();
            for (DocStruct ds : logical.getAllChildren()) {
                if (whiteList.contains(ds.getType().getName())) {
                    docstructList.add(ds);
                }
            }

            if (!docstructList.isEmpty()) {
                // TODO type setzen
                DocStruct ds = gdzfile.getDigitalDocument().createDocStruct(myPrefs.getDocStrctTypeByName("CurriculumVitae"));
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
                        MetadataType mdt =  myPrefs.getMetadataTypeByName("physPageNumber");
                        String value1 = o1.getAllMetadataByType(mdt).get(0).getValue();
                        String value2 = o2.getAllMetadataByType(mdt).get(0).getValue();
                        Integer order1 = Integer.parseInt(value1);
                        Integer order2 = Integer.parseInt(value2);
                        return order1.compareTo(order2);
                    }
                }
                );
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
         * -------------------------------- Metadaten validieren --------------------------------
         */

//        if (ConfigurationHelper.getInstance().isUseMetadataValidation()) {
//            MetadatenVerifizierung mv = new MetadatenVerifizierung();
//            if (!mv.validate(gdzfile, prefs, process)) {
//                return false;
//            }
//        }

        /*
         * -------------------------------- Speicherort vorbereiten und downloaden --------------------------------
         */
        String zielVerzeichnis;
        File benutzerHome;

        zielVerzeichnis = process.getProjekt().getDmsImportImagesPath();
        benutzerHome = new File(zielVerzeichnis);

        /* ggf. noch einen Vorgangsordner anlegen */
        if (process.getProjekt().isDmsImportCreateProcessFolder()) {
            benutzerHome = new File(benutzerHome + File.separator + process.getTitel());
            zielVerzeichnis = benutzerHome.getAbsolutePath();
            /* alte Import-Ordner löschen */
            if (!Helper.deleteDir(benutzerHome)) {
                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Import folder could not be cleared");
                return false;
            }
            /* alte Success-Ordner löschen */
            File successFile = new File(process.getProjekt().getDmsImportSuccessPath() + File.separator + process.getTitel());
            if (!Helper.deleteDir(successFile)) {
                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Success folder could not be cleared");
                return false;
            }
            /* alte Error-Ordner löschen */
            File errorfile = new File(process.getProjekt().getDmsImportErrorPath() + File.separator + process.getTitel());
            if (!Helper.deleteDir(errorfile)) {
                Helper.setFehlerMeldung("Export canceled, Process: " + process.getTitel(), "Error folder could not be cleared");
                return false;
            }

            if (!benutzerHome.exists()) {
                benutzerHome.mkdir();
            }
        }

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

        /*
         * -------------------------------- zum Schluss Datei an gewünschten Ort exportieren entweder direkt in den Import-Ordner oder ins
         * Benutzerhome anschliessend den Import-Thread starten --------------------------------
         */
        boolean externalExport =
                MetadatenHelper.getExportFileformatByName(process.getProjekt().getFileFormatDmsExport(), process.getRegelsatz()) != null;

        if (process.getProjekt().isUseDmsImport()) {
            if (externalExport) {
                /* Wenn METS, dann per writeMetsFile schreiben... */
                writeMetsFile(process, benutzerHome + File.separator + atsPpnBand + ".xml", gdzfile, false);
            } else {
                /* ...wenn nicht, nur ein Fileformat schreiben. */
                gdzfile.write(benutzerHome + File.separator + atsPpnBand + ".xml");
            }

            Helper.setMeldung(null, process.getTitel() + ": ", "DMS-Export started");

            if (!ConfigurationHelper.getInstance().isExportWithoutTimeLimit()) {

                /* Success-Ordner wieder löschen */
                if (process.getProjekt().isDmsImportCreateProcessFolder()) {
                    File successFile = new File(process.getProjekt().getDmsImportSuccessPath() + File.separator + process.getTitel());
                    Helper.deleteDir(successFile);
                }
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
        File sources = new File(process.getSourceDirectory());
        if (sources.exists() && sources.list().length > 0) {
            File destination = new File(benutzerHome + File.separator + atsPpnBand + "_src");
            if (!destination.exists()) {
                destination.mkdir();
            }
            File[] dateien = sources.listFiles();
            for (int i = 0; i < dateien.length; i++) {
                File meinZiel = new File(destination + File.separator + dateien[i].getName());
                Helper.copyFile(dateien[i], meinZiel);
            }
        }

        File ocr = new File(process.getOcrDirectory());
        if (ocr.exists()) {
            File[] folder = ocr.listFiles();
            for (File dir : folder) {
                if (dir.isDirectory() && dir.list().length > 0) {
                    String suffix = dir.getName().substring(dir.getName().lastIndexOf("_"));
                    File destination = new File(benutzerHome + File.separator + atsPpnBand + suffix);
                    if (!destination.exists()) {
                        destination.mkdir();
                    }
                    File[] files = dir.listFiles();
                    for (int i = 0; i < files.length; i++) {
                        File target = new File(destination + File.separator + files[i].getName());
                        Helper.copyFile(files[i], target);
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

            File[] dateien = tifOrdner.listFiles(Helper.dataFilter);
            for (int i = 0; i < dateien.length; i++) {
                File meinZiel = new File(zielTif + File.separator + dateien[i].getName());
                Helper.copyFile(dateien[i], meinZiel);
            }
        }

        if (ConfigurationHelper.getInstance().isExportFilesFromOptionalMetsFileGroups()) {

            List<ProjectFileGroup> myFilegroups = process.getProjekt().getFilegroups();
            if (myFilegroups != null && myFilegroups.size() > 0) {
                for (ProjectFileGroup pfg : myFilegroups) {
                    // check if source files exists
                    if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                        File folder = new File(process.getMethodFromName(pfg.getFolder()));
                        if (folder != null && folder.exists() && folder.list().length > 0) {
                            File[] files = folder.listFiles();
                            for (int i = 0; i < files.length; i++) {
                                File meinZiel = new File(zielTif + File.separator + files[i].getName());
                                Helper.copyFile(files[i], meinZiel);
                            }
                        }
                    }
                }
            }
        }
        File exportFolder = new File(process.getExportDirectory());
        if (exportFolder.exists() && exportFolder.isDirectory()) {
            File[] subdir = exportFolder.listFiles();
            for (File dir : subdir) {
                if (dir.isDirectory() && dir.list().length > 0) {
                    if (!dir.getName().matches(".+\\.\\d+")) {
                        String suffix = dir.getName().substring(dir.getName().lastIndexOf("_"));
                        File destination = new File(benutzerHome + File.separator + atsPpnBand + suffix);
                        if (!destination.exists()) {
                            destination.mkdir();
                        }
                        File[] files = dir.listFiles();
                        for (int i = 0; i < files.length; i++) {
                            File target = new File(destination + File.separator + files[i].getName());
                            Helper.copyFile(files[i], target);
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

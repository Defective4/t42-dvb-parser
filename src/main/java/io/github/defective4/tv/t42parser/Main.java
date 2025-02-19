package io.github.defective4.tv.t42parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import nl.digitalekabeltelevisie.data.mpeg.PID;
import nl.digitalekabeltelevisie.data.mpeg.PesPacketData;
import nl.digitalekabeltelevisie.data.mpeg.TransportStream;
import nl.digitalekabeltelevisie.data.mpeg.pes.ebu.EBUDataField;
import nl.digitalekabeltelevisie.data.mpeg.pes.ebu.EBUPESDataField;
import nl.digitalekabeltelevisie.data.mpeg.pes.ebu.EBUTeletextHandler;
import nl.digitalekabeltelevisie.util.Utils;

public class Main {

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                String filename = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getFile())
                        .getName();
                System.err.println("Usage: java -jar " + filename + " [output directory] [input .ts files...]");
                System.exit(1);
                return;
            }

            Method dataBlockMethod = EBUDataField.class.getDeclaredMethod("getData_block");
            Method offsetMethod = EBUDataField.class.getDeclaredMethod("getOffset");

            dataBlockMethod.setAccessible(true);
            offsetMethod.setAccessible(true);

            File targetDirectory = new File(args[0]);
            if (targetDirectory.isDirectory()) {
                if (targetDirectory.listFiles().length != 0) {
                    System.err.println("Output directory already exists and is not empty");
                    System.exit(2);
                    return;
                }
            } else {
                if (targetDirectory.exists()) {
                    System.err.println("Output path exists and is not an empty directory");
                    System.exit(2);
                    return;
                }
                targetDirectory.mkdirs();
            }

            Map<String, String> links = new LinkedHashMap<>();

            for (String tsPath : Arrays.copyOfRange(args, 1, args.length)) {
                File tsFile = new File(tsPath);
                if (!tsFile.isFile()) {
                    System.err.println("File " + tsPath + " does not exist");
                    System.exit(4);
                    return;
                }

                System.err.println("Loading " + tsFile + "...");

                TransportStream ts = new TransportStream(tsFile);
                ts.parseStream(null);

                System.err.println("Processing " + tsFile + "...");

                for (PID pid : ts.getPids()) {
                    if (pid == null) continue;
                    String label = ((Object) pid.getLabelMaker()).toString();
                    if (label.toLowerCase().startsWith("teletext - ")) {
                        String service = label.substring(label.indexOf('-') + 2);
                        int cIndex = service.indexOf(", ");
                        if (cIndex != -1) service = service.substring(0, cIndex);

                        File serviceDir = new File(targetDirectory, service);
                        serviceDir.mkdirs();

                        System.err.println("Parsing teletext for " + service + "...");
                        EBUTeletextHandler handler = (EBUTeletextHandler) pid.getPidHandler();
                        ts.parsePidStreams(Map.of(pid.getPid(), handler));
                        byte[] data;
                        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                            for (PesPacketData packet : handler.getPesPackets()) {
                                if (packet instanceof EBUPESDataField ebuPacket) {
                                    for (EBUDataField field : ebuPacket.getFieldList()) {
                                        if (field != null) {
                                            byte[] d = (byte[]) dataBlockMethod.invoke(field);
                                            int offset = (int) offsetMethod.invoke(field);
                                            for (int i = 4; i < 46; ++i) {
                                                os.write(Utils.invtab[Byte.toUnsignedInt(d[offset + i])]);
                                            }
                                        }
                                    }
                                }
                            }
                            os.flush();
                            data = os.toByteArray();
                        }
                        System.err.println("Processing teletext for " + service + "...");
                        TeletextCommand.process(data, serviceDir);
                        System.err.println("Saving style files for " + service + "...");
                        saveResource("/teletext.css", new File(serviceDir, "teletext.css"));
                        saveResource("/teletext2.ttf", new File(serviceDir, "teletext2.ttf"));
                        saveResource("/teletext4.ttf", new File(serviceDir, "teletext4.ttf"));
                        List<File> fs = new ArrayList<>();
                        Collections.addAll(fs, serviceDir.listFiles());
                        if (!fs.isEmpty()) {
                            fs.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
                            String first = fs.get(0).getName();
                            if (first.endsWith(".html")) {
                                links.put(service, service + "/" + first);
                            }
                        }
                    }
                }
            }

            if (!links.isEmpty()) {
                System.err.println("Generating index file...");
                Document doc;
                try (InputStream is = Main.class.getResourceAsStream("/index.html")) {
                    doc = Jsoup.parse(is, "utf-8", "");
                }
                Element ul = doc.getElementById("list");
                for (Map.Entry<String, String> entry : links.entrySet()) {
                    Element li = ul.appendElement("li");
                    Element a = li.appendElement("a");
                    a.attr("href", entry.getValue());
                    a.html(entry.getKey());
                }
                try (Writer writer = new FileWriter(new File(targetDirectory, "index.html"), StandardCharsets.UTF_8)) {
                    writer.write(doc.toString());
                }
            }
            System.err.println("All done!");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    private static void saveResource(String resource, File target) throws IOException {
        try (InputStream is = Main.class.getResourceAsStream(resource);
                OutputStream os = Files.newOutputStream(target.toPath())) {
            byte[] buffer = new byte[1024];
            int read;
            while (true) {
                read = is.read(buffer);
                if (read < 0) break;
                os.write(buffer, 0, read);
            }
        } catch (NullPointerException e) {
            throw new IOException(e);
        }
    }
}

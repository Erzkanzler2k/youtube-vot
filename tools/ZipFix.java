import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZipFix — перепаковка APK с корректными именами entry.
 *
 * Зачем: aapt2 на Windows пишет имена ассетов с обратным слэшем
 * (assets/vot\bootstrap.js), что Android может отклонить при установке
 * (валидация имён в libziparchive). Этот инструмент:
 *   1. читает base.apk (создан aapt2) и переписывает все entry,
 *      заменяя '\' на '/' (метод хранения/сжатия сохраняется);
 *   2. дописывает classes.dex (сжатый), если его ещё нет;
 *   3. пишет стандартный валидный zip вместо .NET ZipFile-перепаковки.
 *
 * Запуск: java -cp <buildDir> ZipFix <input.apk> <classes.dex> <output.apk>
 */
public class ZipFix {
    public static void main(String[] args) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[2]))) {
            boolean dexExists = false;
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.isEmpty() || name.equals("classes.dex")) {
                    dexExists |= name.equals("classes.dex");
                    zin.closeEntry();
                    continue;
                }
                ZipEntry out = new ZipEntry(name);
                out.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED) {
                    // STORED требует size/crc — буферизуем
                    byte[] data = readAll(zin);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    out.setMethod(ZipEntry.STORED);
                    out.setSize(data.length);
                    out.setCompressedSize(data.length);
                    out.setCrc(crc.getValue());
                    zout.putNextEntry(out);
                    zout.write(data);
                    zout.closeEntry();
                } else {
                    out.setMethod(ZipEntry.DEFLATED);
                    zout.putNextEntry(out);
                    copy(zin, zout);
                    zout.closeEntry();
                }
                zin.closeEntry();
            }
            if (!dexExists) {
                byte[] dex = Files.readAllBytes(Paths.get(args[1]));
                ZipEntry de = new ZipEntry("classes.dex");
                de.setTime(System.currentTimeMillis());
                zout.putNextEntry(de);
                zout.write(dex);
                zout.closeEntry();
            }
            if (args.length > 3 && !args[3].isEmpty()) {
                addNativeLibraries(zout, args[3]);
            }
        }
    }

    private static void addNativeLibraries(ZipOutputStream zout, String nativeDir) throws IOException {
        Path root = Paths.get(nativeDir);
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(value -> value.toString().endsWith(".so"))::iterator) {
                Path relative = root.relativize(path);
                String entryName = "lib/" + relative.toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(path).toMillis());
                zout.putNextEntry(entry);
                Files.copy(path, zout);
                zout.closeEntry();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
    }
}
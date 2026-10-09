package com.jevforex.lake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lake em disco local.
 *
 * <pre>
 * &lt;root&gt;/bronze/&lt;source&gt;/date=YYYY-MM-DD/&lt;sha256&gt;.&lt;ext&gt;
 * &lt;root&gt;/bronze/&lt;source&gt;/date=YYYY-MM-DD/&lt;sha256&gt;.meta.json
 * </pre>
 * O nome do arquivo é o hash do conteúdo: o mesmo conteúdo nunca é gravado duas vezes.
 */
@Component
public class LocalDiskLakeStorage implements LakeStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskLakeStorage.class);

    private final Path root;
    private final ObjectMapper json;

    public LocalDiskLakeStorage(LakeProperties props) {
        this.root = Path.of(props.root()).toAbsolutePath().normalize();
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(root.resolve("bronze"));
            Files.createDirectories(root.resolve("silver"));
            Files.createDirectories(root.resolve("gold"));
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui criar o lake em " + root, e);
        }
        log.info("Data lake em {}", root);
    }

    @Override
    public StoredObject writeBronze(String source, Instant firstSeenAt, String extension, byte[] content,
                                    Map<String, Object> meta) {
        String sha = sha256(content);
        String date = firstSeenAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Path dir = root.resolve("bronze").resolve(safe(source)).resolve("date=" + date);
        Path file = dir.resolve(sha + "." + safe(extension));
        Path metaFile = dir.resolve(sha + ".meta.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                return new StoredObject(root.relativize(file), sha, true);
            }
            // escreve em arquivo temporário e renomeia: nunca deixa arquivo pela metade
            Path tmp = dir.resolve(sha + ".tmp");
            Files.write(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            Map<String, Object> m = new LinkedHashMap<>(meta);
            m.put("source", source);
            m.put("sha256", sha);
            m.put("first_seen_at", firstSeenAt.toString());
            m.put("ingested_at", Instant.now().toString());
            m.put("bytes", content.length);
            json.writeValue(metaFile.toFile(), m);
            return new StoredObject(root.relativize(file), sha, false);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gravar no bronze: " + file, e);
        }
    }

    @Override
    public byte[] read(Path relativePath) {
        try {
            return Files.readAllBytes(root.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}

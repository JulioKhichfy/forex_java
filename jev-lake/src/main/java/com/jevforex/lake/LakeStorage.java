package com.jevforex.lake;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Acesso ao data lake. Hoje: disco local. Amanhã, se quiser: outra implementação (ex.: S3)
 * sem mudar o resto do código (documento mestre, capítulo 4).
 */
public interface LakeStorage {

    /**
     * Grava um arquivo bruto no bronze, imutável, com um .meta.json ao lado.
     *
     * @param source      fonte (ex.: cb_web)
     * @param firstSeenAt quando o SEU sistema viu o dado (define a partição date=)
     * @param extension   extensão do arquivo (html, xml, csv, json)
     * @param content     bytes exatamente como chegaram
     * @param meta        metadados (url, issuer, published_at, …)
     * @return o arquivo gravado (caminho relativo à raiz do lake + hash)
     */
    StoredObject writeBronze(String source, Instant firstSeenAt, String extension, byte[] content,
                             Map<String, Object> meta);

    byte[] read(Path relativePath);

    Path root();

    record StoredObject(Path relativePath, String sha256, boolean alreadyExisted) {
    }
}

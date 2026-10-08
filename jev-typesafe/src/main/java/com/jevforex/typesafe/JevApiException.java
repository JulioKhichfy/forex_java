package com.jevforex.typesafe;

/** Erro de chamada ao Jev, com o status HTTP e o request id da TypeSafe para suporte. */
public class JevApiException extends RuntimeException {

    private final int status;
    private final String requestId;
    private final String body;

    public JevApiException(int status, String requestId, String body, String message) {
        super(message);
        this.status = status;
        this.requestId = requestId;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String requestId() {
        return requestId;
    }

    public String body() {
        return body;
    }

    /** Dica em português para os erros documentados da API. */
    public String hint() {
        return switch (status) {
            case 401 -> "Chave inválida ou ausente. Confira TYPESAFE_API_KEY / JEV_API_KEY_FILE.";
            case 402 -> "Sem créditos. Adicione saldo no console da TypeSafe.";
            case 422 -> "Corpo da requisição inválido. O campo com problema vem no corpo da resposta.";
            case 429 -> "Limite de requisições excedido. Aguarde e tente de novo.";
            case 529 -> "TypeSafe sobrecarregada. Aguarde e tente de novo.";
            default -> "Veja o corpo da resposta e o request id.";
        };
    }
}

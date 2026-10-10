package com.jevforex.app.trade;

/** Acesso de teste aos auxiliares do OrderService (pacote). */
public final class OrderServiceAccess {

    private OrderServiceAccess() {
    }

    public static int usdSign(String symbol, String side) {
        return OrderService.usdSign(symbol, side);
    }
}

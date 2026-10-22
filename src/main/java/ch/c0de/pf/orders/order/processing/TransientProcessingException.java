package ch.c0de.pf.orders.order.processing;

public class TransientProcessingException extends OrderProcessingException {

    public TransientProcessingException(String message) {
        super(message);
    }
}

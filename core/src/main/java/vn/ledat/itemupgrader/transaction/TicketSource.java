package vn.ledat.itemupgrader.transaction;

/** Called on the worker only after a DRAW_INTENT CAS is acknowledged. Test fixtures may inject boundary tickets. */
@FunctionalInterface
public interface TicketSource { long draw(); }

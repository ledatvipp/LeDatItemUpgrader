package vn.ledat.itemupgrader.output;

import java.util.Objects;
import vn.ledat.itemupgrader.failure.FailurePolicy;
import vn.ledat.itemupgrader.transfer.TransferPolicy;

/** Full policy terms, not a mutable config ID reference. */
public record OutputSpec(TransferPolicy transfer, FailurePolicy failure) {
    public OutputSpec { Objects.requireNonNull(transfer); Objects.requireNonNull(failure); }
}

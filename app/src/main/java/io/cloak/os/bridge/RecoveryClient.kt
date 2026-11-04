package io.cloak.os.bridge

import android.content.Context
import android.content.Intent

object RecoveryClient {
    /** The unsigned recover request. The token itself never goes into it, only a proof. */
    fun intent(newSecret: String, ts: Long, proof: String): Intent =
        Intent(Commands.ACTION)
            .putExtra(Commands.EXTRA_CMD, Commands.RECOVER)
            .putExtra(RecoveryProof.EXTRA_SECRET, newSecret)
            .putExtra(RecoveryProof.EXTRA_TS, ts)
            .putExtra(RecoveryProof.EXTRA_PROOF, proof)

    /**
     * Raises the system's unpause or work PIN prompt. The command is unsigned and is rejected
     * by the space, which is fine, because the prompt is the system's own.
     */
    fun wake(context: Context): Result<Unit> = runCatching {
        context.startActivity(
            Intent(Commands.ACTION).putExtra(Commands.EXTRA_CMD, Commands.UNLOCK)
        )
    }
}
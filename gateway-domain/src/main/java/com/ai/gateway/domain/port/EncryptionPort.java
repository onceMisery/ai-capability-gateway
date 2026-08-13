package com.ai.gateway.domain.port;

/**
 * Port for encrypting and decrypting write-operation parameters.
 *
 * <p>(Sensitive Data) specifies that write-operation
 * parameters use KMS-managed key envelope encryption. Original model
 * parameters are encrypted and stored only when there is a genuine audit
 * need, with a short retention period.</p>
 *
 * <p>(Prepare): the Prepare phase persists
 * {@code encryptedArguments} — the bound parameters encrypted at rest.
 * The {@code argumentsDigest} allows integrity verification without
 * decryption. The confirmation token is bound to the
 * {@code argumentsDigest} to detect tampering.</p>
 *
 * <p>additional rules:</p>
 * <ul>
 * <li>Retrieval text, prompts, model responses, and Provider data are
 * not recorded in full by default.</li>
 * <li>Logs record summaries, field names, lengths, stable error codes,
 * and irreversible hashes.</li>
 * <li>Audit queries themselves must be authorized and audited.</li>
 * <li>Expired data is deleted by verifiable cleanup tasks; audit index
 * retention policy is determined by compliance requirements.</li>
 * </ul>
 *
 * <p>Adapters implementing this port integrate with the platform's KMS
 * (e.g., AWS KMS, HashiCorp Vault Transit, or cloud KMS) for envelope
 * encryption. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see com.ai.gateway.domain.model.OperationRecord
 * @since 0.1.0
 */
public interface EncryptionPort {

    /**
     * Encrypts the given plaintext using KMS-managed envelope encryption.
     *
     * <p> the Prepare phase encrypts bound
     * parameters at rest. The ciphertext is stored in the
     * {@code encryptedArguments} field of the operation record. An
     * arguments digest is computed separately to allow integrity
     * verification without decryption.</p>
     *
     * @param plaintext the plaintext to encrypt
     * @return the ciphertext; never {@code null}
     * @throws RuntimeException if encryption fails
     */
    String encrypt(String plaintext);

    /**
     * Decrypts the given ciphertext using KMS-managed envelope encryption.
     *
     * <p>(Confirm): the Confirm phase decrypts the stored
     * arguments to execute the Provider call with the originally bound
     * parameters. Decryption is only performed during the Confirm phase,
     * not during retrieval, routing, or audit.</p>
     *
     * @param ciphertext the ciphertext to decrypt
     * @return the plaintext; never {@code null}
     * @throws RuntimeException if decryption fails
     */
    String decrypt(String ciphertext);
}

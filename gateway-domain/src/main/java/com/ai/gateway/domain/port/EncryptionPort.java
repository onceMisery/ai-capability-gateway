package com.ai.gateway.domain.port;

/**
 * 加解密写操作参数的端口。
 *
 * <p>（敏感数据）规定写操作参数使用 KMS 托管的密钥信封加密。原始模型参数仅在确有审计
 * 需要时才加密存储，且保留期较短。</p>
 *
 * <p>（Prepare）：Prepare 阶段持久化 {@code encryptedArguments}——静态加密后的绑定参数。
 * {@code argumentsDigest} 允许在不解密的情况下校验完整性。确认令牌绑定到
 * {@code argumentsDigest} 以检测篡改。</p>
 *
 * <p>附加规则：</p>
 * <ul>
 * <li>检索文本、提示词、模型响应与 Provider 数据默认不全量记录。</li>
 * <li>日志只记录摘要、字段名、长度、稳定错误码与不可逆哈希。</li>
 * <li>审计查询本身也必须经过鉴权与审计。</li>
 * <li>过期数据由可验证的清理任务删除；审计索引保留策略由合规要求决定。</li>
 * </ul>
 *
 * <p>实现此端口的适配器与平台 KMS（如 AWS KMS、HashiCorp Vault Transit 或云 KMS）集成
 * 以完成信封加密。该端口是纯粹的领域抽象，不依赖任何框架。</p>
 *
 * @see com.ai.gateway.domain.model.OperationRecord
 * @since 0.1.0
 */
public interface EncryptionPort {

    /**
     * 使用 KMS 托管的信封加密对给定明文进行加密。
     *
     * <p>规定：Prepare 阶段将绑定参数静态加密。密文存储在操作记录的
     * {@code encryptedArguments} 字段中。参数摘要另行计算，以便在不解密的情况下校验完整性。</p>
     *
     * @param plaintext 待加密的明文
     * @return 密文；永不为 {@code null}
     * @throws RuntimeException 当加密失败时
     */
    String encrypt(String plaintext);

    /**
     * 使用 KMS 托管的信封加密对给定密文进行解密。
     *
     * <p>（Confirm）：Confirm 阶段解密存储的参数，以原始绑定参数执行 Provider 调用。
     * 解密仅在 Confirm 阶段进行，不在检索、路由或审计阶段执行。</p>
     *
     * @param ciphertext 待解密的密文
     * @return 明文；永不为 {@code null}
     * @throws RuntimeException 当解密失败时
     */
    String decrypt(String ciphertext);
}

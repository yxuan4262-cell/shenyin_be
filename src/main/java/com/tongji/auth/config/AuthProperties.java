package com.tongji.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 认证相关配置属性，绑定前缀 {@code auth.*}。
 *
 * <p>包含以下分组：</p>
 * - Jwt：令牌签发与验证配置；
 * - Verification：验证码发送与校验配置；
 * - Password：密码策略与加密强度配置。
 *
 * <p><b>为何类上要有 {@code @Validated}</b>：配置绑定默认只检查<b>类型</b>能不能转，
 * 不检查<b>值</b>合不合理。{@code auth.jwt.access-token-ttl: -5m} 能顺利绑定成一个
 * 负的 Duration，启动一路绿灯，然后在<b>每次登录</b>时由 {@code JwtClaimsSet.Builder}
 * 抛 {@code IllegalArgumentException: expiresAt must be after issuedAt}，
 * 冒到 GlobalExceptionHandler 变成 500——全站无法登录，而启动日志里什么都没有。
 * 加上校验后，这个错误在<b>启动阶段</b>就炸，而且带着明确的字段名。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * JWT 配置项。
     * <p>
     * {@code @Valid} 不能省：Bean Validation 默认<b>不级联</b>到嵌套对象。
     * 少了它，下面 Jwt 内部的约束一条都不会被执行，而类上看起来「已经加了 @Validated」。
     * 这种「校验装好了但不生效」比没装更难发现。
     */
    @Valid
    private final Jwt jwt = new Jwt();
    /** 验证码配置项。 */
    private final Verification verification = new Verification();
    /** 密码策略配置项。 */
    private final Password password = new Password();

    @Data
    public static class Jwt {
        /** JWT 签发者标识（iss）。 */
        private String issuer = "zhiguang";
        /** 访问令牌有效期（TTL）。 */
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        /** 刷新令牌有效期（TTL）。 */
        private Duration refreshTokenTtl = Duration.ofDays(7);
        /** JWK 密钥标识（kid），用于下游校验与轮换。 */
        private String keyId = "zhiguang-key";
        /** RSA 私钥 PEM（PKCS#8）资源。 */
        private Resource privateKey;
        /** RSA 公钥 PEM（X.509）资源。 */
        private Resource publicKey;

        /**
         * 两个 TTL 必须为正，且 refresh 不得短于 access。
         * <p>
         * 用 {@code @AssertTrue} 而不是 {@code @Positive}：后者对 {@code java.time.Duration}
         * 的支持取决于校验实现的版本，前者只依赖 Duration 自己的 API，行为确定。
         * <p>
         * 最后一条是<b>跨字段</b>约束，单看每个字段都合法、合在一起却不成立：
         * 如果 refresh 比 access 还短，那「用长命的换短命的」这件事本身就设反了——
         * 用户会在 access 还没过期时就发现已经续不了期。
         * 这类约束无法用字段级注解表达，只能写成方法。
         */
        @AssertTrue(message = "auth.jwt 的 access-token-ttl 与 refresh-token-ttl 必须为正数，且 refresh-token-ttl 不得短于 access-token-ttl")
        public boolean isTtlSane() {
            if (accessTokenTtl == null || refreshTokenTtl == null) {
                return false;
            }
            if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
                return false;
            }
            if (refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
                return false;
            }
            return refreshTokenTtl.compareTo(accessTokenTtl) >= 0;
        }
    }

    /**
     * 验证码配置：位数、有效期、最大尝试次数、发送间隔与每日上限。
     */
    @Data
    public static class Verification {
        /** 验证码位数。 */
        private int codeLength = 6;
        /** 验证码有效时间。 */
        private Duration ttl = Duration.ofMinutes(5);
        /** 最大校验尝试次数。 */
        private int maxAttempts = 5;
        /** 同标识连续发送的最小间隔。 */
        private Duration sendInterval = Duration.ofSeconds(60);
        /** 同标识每日发送上限。 */
        private int dailyLimit = 10;
    }

    /** 密码策略配置。 */
    @Data
    public static class Password {
        /** 密码哈希强度（BCrypt cost）。 */
        private int bcryptStrength = 12;
        /** 密码最小长度。 */
        private int minLength = 8;
    }
}

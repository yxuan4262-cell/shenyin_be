package com.tongji.auth.token;

import com.tongji.auth.config.AuthConfiguration;
import com.tongji.auth.config.AuthProperties;
import com.tongji.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
        properties.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));
        AuthConfiguration configuration = new AuthConfiguration(properties);
        JwtEncoder encoder = configuration.jwtEncoder();
        JwtDecoder decoder = configuration.jwtDecoder();
        jwtService = new JwtService(encoder, decoder, properties);
    }

    @Test
    void issueTokenPairAndDecode() {
        User user = User.builder()
                .id(123L)
                .nickname("tester")
                .build();

        TokenPair tokenPair = jwtService.issueTokenPair(user);

        assertThat(tokenPair.accessToken()).isNotBlank();
        assertThat(tokenPair.refreshToken()).isNotBlank();
        assertThat(tokenPair.refreshTokenId()).isNotBlank();

        Jwt accessJwt = jwtService.decode(tokenPair.accessToken());
        assertThat(jwtService.extractTokenType(accessJwt)).isEqualTo("access");
        assertThat(jwtService.extractUserId(accessJwt)).isEqualTo(123L);

        Jwt refreshJwt = jwtService.decode(tokenPair.refreshToken());
        assertThat(jwtService.extractTokenType(refreshJwt)).isEqualTo("refresh");
        assertThat(jwtService.extractUserId(refreshJwt)).isEqualTo(123L);
        assertThat(jwtService.extractTokenId(refreshJwt)).isEqualTo(tokenPair.refreshTokenId());
    }

    // ==================================================================
    //  base64url 工具：用来模拟攻击者手工改写 token
    // ==================================================================
    //
    //  JWT 用的不是标准 base64，而是 base64url（RFC 4648 §5），三处差异：
    //    '+' -> '-'      '/' -> '_'      去掉尾部 '=' 填充
    //  Java 的 Base64.getUrlEncoder()/getUrlDecoder() 原生支持，不用自己实现。
    //
    //  最容易踩的坑是 withoutPadding()：标准编码器会补 '='，而 JWT 里不能有 '='。
    //  留着填充会导致 token 字符串变长，Nimbus 解析时直接报 Malformed token ——
    //  那时你会以为是签名不对，其实是编码格式不对，排查方向全错。

    /**
     * 取出 token 的 payload 段并解码成 JSON 字符串。
     *
     * @param token 完整 JWT
     * @return payload 的明文 JSON
     */
    private static String decodePayload(String token) {
        String payload = token.split("\\.")[1];
        return new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    }

    /**
     * 把 JSON 字符串编码成 base64url（无填充）。
     *
     * @param json payload 明文
     * @return base64url 编码结果
     */
    private static String encodePayload(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 只替换 payload，header 与 signature 原样保留。
     *
     * <p>这正是「攻击者改内容但不重新签名」的情形：他没有私钥，
     * 只能保留原签名，于是 header.payload 变了而 signature 没变。</p>
     *
     * @param token          原始 JWT
     * @param newPayloadJson 改写后的 payload 明文
     * @return 篡改后的 JWT
     */
    private static String withPayload(String token, String newPayloadJson) {
        String[] parts = token.split("\\.");
        return parts[0] + "." + encodePayload(newPayloadJson) + "." + parts[2];
    }

    /**
     * 篡改 payload（uid 123 -> 999）而保留原签名，解码必须被拒绝。
     *
     * <p>对应线上实测 CASE B：同样的攻击打到 HTTP 接口上，返回的是
     * 401 + 空 body + WWW-Authenticate 头里写 "Invalid signature"。
     * 但单元测试里没有过滤器链，直接调 JwtDecoder，所以拿到的是原始异常。</p>
     *
     * <p><b>同一个失败，两条出口</b>：单元测试看到 BadJwtException，
     * HTTP 客户端只看到 401。别把两者的断言搞混。</p>
     */
    @Test
    void tamperedPayloadIsRejected() {
        User user = User.builder()
                .id(123L)
                .nickname("tester")
                .build();
        String original = jwtService.issueTokenPair(user).accessToken();

        // 对照组：先证明原始 token 是好的。
        //   少了这一步，测试失败时你无法区分「篡改检测生效了」和「测试本身就坏了」。
        assertThat(jwtService.extractUserId(jwtService.decode(original))).isEqualTo(123L);

        // 攻击：把 uid 和 sub 都改成 999，签名原样保留。
        String payload = decodePayload(original);
        assertThat(payload).contains("\"uid\":123");          // 改之前长这样
        String tamperedJson = payload
                .replace("\"uid\":123", "\"uid\":999")
                .replace("\"sub\":\"123\"", "\"sub\":\"999\"");
        String forged = withPayload(original, tamperedJson);

        // 先确认攻击确实生效了，再去断言它被拒。
        //   如果 replace 因为格式不匹配而什么都没改，forged 会等于 original，
        //   那么下面的断言会因为「原始 token 合法」而失败 —— 但失败原因是测试写错了，
        //   不是签名机制生效了。这两条前置断言就是把这种情况排除掉。
        assertThat(decodePayload(forged)).contains("\"uid\":999");
        assertThat(forged).isNotEqualTo(original);
        assertThat(forged.split("\\.")).hasSize(3);            // 结构完整，看起来像个正常 token

        // 核心断言：解码必须抛异常。
        assertThatThrownBy(() -> jwtService.decode(forged))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("Invalid signature");
    }

    /**
     * 篡改签名而保留 payload，解码必须被拒绝。
     *
     * <p>与 {@link #tamperedPayloadIsRejected()} 的区别：那个改内容不改签名，
     * 这个改签名不改内容。两者殊途同归：签名算的是
     * {@code header_b64 + "." + payload_b64} 这整个字符串的指纹，
     * 等式两边任何一侧变了，指纹就对不上。</p>
     */
    @Test
    void tamperedSignatureIsRejected() {
        User user = User.builder()
                .id(123L)
                .nickname("tester")
                .build();
        String original = jwtService.issueTokenPair(user).accessToken();

        // 对照组：先证明原始 token 是好的。
        assertThat(jwtService.extractUserId(jwtService.decode(original))).isEqualTo(123L);

        String[] parts = original.split("\\.");
        assertThat(parts).hasSize(3);

        // 签名是 342 字符 base64url = 256 字节 = RSA-2048。
        //   签名长度恒等于密钥长度 / 8，所以可以从这里反推密钥强度。
        String signature = parts[2];
        assertThat(signature).hasSize(342);

        // 翻转最后一个字符，而不是直接接一段 "AAAAAA"。
        //   原因：接固定字符串有极小概率刚好等于原签名末尾，那样测试会失败得莫名其妙。
        //   翻转则保证一定不同，下面那条 isNotEqualTo 断言永不会误报。
        char last = signature.charAt(signature.length() - 1);
        char flipped = (last == 'A') ? 'B' : 'A';
        String tamperedSignature = signature.substring(0, signature.length() - 1) + flipped;

        // 两条前置断言：长度不变（仍是合法的 base64url 串），内容确实变了。
        //   长度为何重要：改短了 Nimbus 会在【解析阶段】就失败并报 Malformed token，
        //   那时测到的是「格式不对」而不是「签名不对」——两件事必须分开。
        //   字符集同理：换成 '+' '/' '=' 或非 ASCII 也会变成 Malformed。
        assertThat(tamperedSignature).hasSize(signature.length());
        assertThat(tamperedSignature).isNotEqualTo(signature);

        String forged = parts[0] + "." + parts[1] + "." + tamperedSignature;

        // 关键区别断言：payload 完好无损。
        //   这条把本测试与 tamperedPayloadIsRejected 彻底区分开：
        //   那边内容变了签名没变，这边签名变了内容没变。
        assertThat(decodePayload(forged)).isEqualTo(decodePayload(original));

        // 核心断言。注意服务端【不应该】区分这两种篡改：
        //   两者都是「签名对不上」，报同一个错才是对的。
        //   如果分别报「payload 被改」和「签名被改」，等于给攻击者一个 oracle，
        //   他可以逐字节试探哪一部分影响了校验。
        assertThatThrownBy(() -> jwtService.decode(forged))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("Invalid signature");
    }

    /**
     * 签名完全合法、内容完全合法，仅仅因为过了 exp 而必须被拒绝。
     *
     * <p><b>为什么不用 Thread.sleep</b>：sleep 让测试变慢且不确定——
     * 机器卡一下就 flaky，而 flaky 测试比没测试更坏，因为它会训练你忽略红灯。</p>
     *
     * <p><b>为什么不用 Clock.fixed</b>：{@code JwtService} 里的时钟是
     * {@code private final Clock clock = Clock.systemUTC();}，直接初始化在字段上，
     * 构造器注入不进去。这正是那个设计缺陷的后果。</p>
     *
     * <p><b>为什么也不能把 TTL 设成负数</b>：我一开始就是这么干的，被框架当场拒了——
     * {@code JwtClaimsSet.Builder} 有硬校验，抛
     * {@code IllegalArgumentException: expiresAt must be after issuedAt}。
     * 也就是说【无法通过配置造出一个过期 token】，这条路被堵死了。</p>
     *
     * <p><b>顺带一个真实发现</b>：{@code AuthProperties} 对 ttl 没有任何校验。
     * 谁要是真把 {@code auth.jwt.access-token-ttl} 配成负数或 0，
     * 上面那个 IllegalArgumentException 会在【每次登录】时抛出，
     * 冒到 GlobalExceptionHandler.handleGeneric → 全站登录 500。
     * 一个配置项的取值范围没人管，就能让整个认证功能瘫痪。</p>
     *
     * <p><b>最终办法</b>：绕过 JwtService，直接用 JwtEncoder 构造 claims，
     * 让 iat 和 exp 都落在过去（但保持 exp &gt; iat 满足校验）。
     * 签出来的 token 签名完全合法——因为它就是用真私钥签的——唯一的问题是时间。
     * 这正是真实世界里过期 token 的样子。零等待、完全确定。</p>
     */
    @Test
    void expiredTokenIsRejected() {
        AuthProperties props = new AuthProperties();
        props.getJwt().setIssuer("test-issuer");
        props.getJwt().setPrivateKey(new ClassPathResource("keys/private.pem"));
        props.getJwt().setPublicKey(new ClassPathResource("keys/public.pem"));

        AuthConfiguration configuration = new AuthConfiguration(props);
        JwtEncoder encoder = configuration.jwtEncoder();
        JwtDecoder decoder = configuration.jwtDecoder();

        // iat = 1 小时前，exp = iat + 15 分钟 = 45 分钟前。
        //   同时满足两个相互矛盾的要求：exp > iat（过框架校验）、exp < now（已过期）。
        Instant now = Instant.now();
        Instant issuedAt = now.minusSeconds(3600);
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(15));
        assertThat(expiresAt).isAfter(issuedAt);      // 框架的校验条件，自己先确认
        assertThat(expiresAt).isBefore(now);          // 但确实已经过期

        String expired = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("test-issuer")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject("123")
                .id(UUID.randomUUID().toString())
                .claim("token_type", "access")
                .claim("uid", 123L)
                .claim("nickname", "tester")
                .build())).getTokenValue();

        // 前置断言：结构完好、内容正确。它唯一的毛病就是过期了。
        //   这一步排除「签发本身就坏了」的可能，否则下面的失败无法归因。
        assertThat(expired.split("\\.")).hasSize(3);
        String payload = decodePayload(expired);
        assertThat(payload).contains("\"uid\":123");
        assertThat(payload).contains("\"token_type\":\"access\"");

        // 直接从 payload 明文里把时间戳抠出来核对——还记得吗，payload 是 base64 明文，
        //   不需要任何密钥就能读。这里正好利用了这一点做断言。
        long exp = Long.parseLong(payload.replaceAll(".*\"exp\":(\\d+).*", "$1"));
        assertThat(Instant.ofEpochSecond(exp)).isBefore(Instant.now());

        // 对照组：同一个 encoder/decoder，签一个没过期的，必须能正常解。
        //   这条很重要：它证明上面的失败确实是因为【过期】，
        //   而不是 encoder/decoder 装配错了（比如密钥读错、算法不匹配）。
        //   顺带走了一遍项目真实的 extractUserId() 提取路径。
        String healthy = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("test-issuer")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .subject("123")
                .id(UUID.randomUUID().toString())
                .claim("token_type", "access")
                .claim("uid", 123L)
                .build())).getTokenValue();
        assertThat(jwtService.extractUserId(decoder.decode(healthy))).isEqualTo(123L);

        // 核心断言：签名完全合法，仅因过期而被拒。
        //
        // 【实测出来的精确类型】这里断言 JwtValidationException，不是 BadJwtException。
        //   写 BadJwtException 也能通过——因为 JwtValidationException 继承自它。
        //   但那个断言太宽松，会掩盖一个有实际意义的区别：
        //
        //     篡改 payload / 篡改签名 → BadJwtException        "Invalid signature"
        //     仅仅过期               → JwtValidationException  "Jwt expired at <精确时刻>"
        //
        //   为何这个区别重要：
        //     BadJwtException        = token 本身是坏的 → 不可恢复，只能重新登录
        //     JwtValidationException = token 合法，只是时间过了 → 可恢复，该拿 refresh token 续期
        //   前端本可以据此决定「静默刷新」还是「跳登录页」。
        //
        //   讽刺的是到了 HTTP 层两者都变成 401 + 空 body，客户端无法区分，
        //   只能一律先试刷新、刷新失败再跳登录。区别信息只残留在
        //   WWW-Authenticate 头的非标准文本里（线上实测 CASE B/C vs CASE E）。
        //
        //   想知道确切类型别猜：把断言临时改成一个错的类型（比如 IllegalStateException），
        //   跑一次，AssertJ 会把真实类型和完整 message 打印出来。这条断言就是这么定下来的。
        assertThatThrownBy(() -> decoder.decode(expired))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }
}

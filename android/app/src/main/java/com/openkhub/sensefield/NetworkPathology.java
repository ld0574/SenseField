package com.openkhub.sensefield;

import java.util.Collection;
import java.util.TreeSet;

/**
 * 「网络通不通」那一行读数：入参是采集侧已经读好的现值（传输类型集合、VALIDATED、下行 kbps、时刻），
 * 出参是一行结构化读数，挂在既有 {@code device} 采样链上，不新增通道。
 *
 * <p>纯函数：零平台 import、不做任何系统调用、一次也不发起网络请求。平台
 * {@code NetworkCapabilities} 的常量在这里一律镜像成普通 int（本轮用 javap -constants
 * 在 android-36 的平台 jar 上复验过：VALIDATED=16、TRANSPORT_CELLULAR=0、TRANSPORT_WIFI=1，
 * {@code getLinkDownstreamBandwidthKbps()} 存在），所以全部规则都能在 JVM 单元测试里逐条钉死。
 * 真正的读值采集在 {@code HyperOsSystemProbe} 那一侧。
 *
 * <p>四条口径，一条也不许漂：
 * <ul>
 *   <li>确实没有网络 ⇒ {@code type=none}，其余字段 {@code unavailable}：一个数也不造。</li>
 *   <li>读不到 ⇒ {@code unknown}。「读不到」与「没有网络」是两件事，不许把前者播成后者。</li>
 *   <li>下行带宽取不到时平台返回 0：0 与负数一律记 {@code unknown}，绝不播成「0 kbps 弱网」，
 *       也绝不拿 0 当真实带宽。</li>
 *   <li>多传输并存 ⇒ 全部列名，不挑一个代表整体。</li>
 * </ul>
 *
 * <p>本类不判定「弱网」，也一个阈值都不设：下行多少算弱，本机没有真机依据，写进读数就是把猜的
 * 口径播成事实。读数只说三件事——有没有网、验过没有、下行估多少。
 */
final class NetworkPathology {

    /** 既有 {@code device} 事件再追加的那一个键；既有键一字不动。 */
    static final String READING_KEY = "network_pathology";

    /* 平台 NetworkCapabilities 常量的镜像表：键名与取值同平台，只为让纯函数不 import 平台类型。 */
    /** 平台 NET_CAPABILITY_VALIDATED。 */
    static final int CAP_VALIDATED = 16;
    /** 平台 TRANSPORT_CELLULAR。 */
    static final int TRANSPORT_CELLULAR = 0;
    /** 平台 TRANSPORT_WIFI。 */
    static final int TRANSPORT_WIFI = 1;
    /** 平台 TRANSPORT_BLUETOOTH。 */
    static final int TRANSPORT_BLUETOOTH = 2;
    /** 平台 TRANSPORT_ETHERNET。 */
    static final int TRANSPORT_ETHERNET = 3;
    /** 平台 TRANSPORT_VPN。 */
    static final int TRANSPORT_VPN = 4;
    /** 平台 TRANSPORT_WIFI_AWARE。 */
    static final int TRANSPORT_WIFI_AWARE = 5;
    /** 平台 TRANSPORT_LOWPAN。 */
    static final int TRANSPORT_LOWPAN = 6;
    /** 平台 TRANSPORT_USB。 */
    static final int TRANSPORT_USB = 8;
    /** 平台 TRANSPORT_THREAD。 */
    static final int TRANSPORT_THREAD = 9;
    /** 平台 TRANSPORT_SATELLITE。 */
    static final int TRANSPORT_SATELLITE = 10;

    /** 采集侧点名的那四个传输码：镜像表与采集面共用这一份，避免两处各写各的。 */
    static final int[] MIRRORED_TRANSPORTS = {
            TRANSPORT_CELLULAR, TRANSPORT_WIFI, TRANSPORT_BLUETOOTH, TRANSPORT_ETHERNET,
            TRANSPORT_VPN, TRANSPORT_WIFI_AWARE, TRANSPORT_LOWPAN, TRANSPORT_USB,
            TRANSPORT_THREAD, TRANSPORT_SATELLITE};

    /** 系统明说「当前没有连接」时用这一档；它与 {@link #UNKNOWN} 不是同一件事。 */
    static final String NO_NETWORK = "none";
    /** 无网络时其余字段的取值：不是「读不到」，是「这一场没有网络，所以根本无从谈起」。 */
    static final String UNAVAILABLE = "unavailable";
    /** 读不到、叫不出名字、0 与负数带宽：一律落这一档，不猜。 */
    static final String UNKNOWN = "unknown";

    private NetworkPathology() { /* 纯函数只暴露静态方法。 */ }

    /**
     * 一行结构化读数：{@code net at=<ms> type=<...> validated=<...> down_kbps=<...>}。
     *
     * @param atMs       采集时刻，与既有读数同源的单调时钟
     * @param transports 活动网络的传输类型集合；空集合＝系统说没有网络，{@code null}＝这一项读不到
     * @param validated  {@code null}＝读不到；否则为平台 VALIDATED 能力的现值
     * @param downKbps   {@code null}、0、负数都按「取不到」处理，绝不落成一个真实带宽数字
     */
    static String reading(long atMs, Collection<Integer> transports, Boolean validated, Integer downKbps) {
        StringBuilder out = new StringBuilder("net");
        out.append(" at=").append(atMs);
        if (transports == null) {
            // 连传输类型这一项都读不到：整行落 unknown，不拿「没有网络」冒充「读不到」。
            return out.append(" type=").append(UNKNOWN)
                    .append(" validated=").append(UNKNOWN)
                    .append(" down_kbps=").append(UNKNOWN).toString();
        }
        if (transports.isEmpty()) {
            // 系统明说没有活动网络：其余字段一律 unavailable，谁都不许替它造一个值。
            return out.append(" type=").append(NO_NETWORK)
                    .append(" validated=").append(UNAVAILABLE)
                    .append(" down_kbps=").append(UNAVAILABLE).toString();
        }
        return out.append(" type=").append(transportNames(transports))
                .append(" validated=").append(validatedName(validated))
                .append(" down_kbps=").append(downlinkName(downKbps)).toString();
    }

    /** 多传输并存就把每一个都列出来，按传输码升序排，一次也不许只报第一个。 */
    private static String transportNames(Collection<Integer> transports) {
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer code : transports) if (code != null) sorted.add(code);
        if (sorted.isEmpty()) return UNKNOWN; // 集合非空但一个可用传输码也没有：仍算读不到。
        StringBuilder names = new StringBuilder();
        for (Integer code : sorted) {
            if (names.length() > 0) names.append('+');
            names.append(transportName(code.intValue()));
        }
        return names.toString();
    }

    /** 镜像表里叫得出名字的用名字，叫不出的一律 {@code unknown}，不替未知传输编名字。 */
    static String transportName(int code) {
        switch (code) {
            case TRANSPORT_CELLULAR: return "cellular";
            case TRANSPORT_WIFI: return "wifi";
            case TRANSPORT_BLUETOOTH: return "bluetooth";
            case TRANSPORT_ETHERNET: return "ethernet";
            case TRANSPORT_VPN: return "vpn";
            case TRANSPORT_WIFI_AWARE: return "wifi_aware";
            case TRANSPORT_LOWPAN: return "lowpan";
            case TRANSPORT_USB: return "usb";
            case TRANSPORT_THREAD: return "thread";
            case TRANSPORT_SATELLITE: return "satellite";
            default: return UNKNOWN;
        }
    }

    private static String validatedName(Boolean validated) {
        if (validated == null) return UNKNOWN;
        return validated.booleanValue() ? "true" : "false";
    }

    /**
     * 下行带宽：平台取不到时返回 0，所以 0 与负数一样按「取不到」落 {@code unknown}。
     * 只有真正读到的正数才许带上数字，且不带任何「弱／强」判词。
     */
    static String downlinkName(Integer downKbps) {
        if (downKbps == null) return UNKNOWN;
        int kbps = downKbps.intValue();
        return kbps > 0 ? String.valueOf(kbps) : UNKNOWN;
    }
}

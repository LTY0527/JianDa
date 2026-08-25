package cn.jianda.publicapi;

import cn.jianda.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class SupportedRegions {
    static final String DEFAULT_CODE = "310113102";
    private static final Map<String, Region> REGIONS = new LinkedHashMap<>();

    static {
        register("310113102", "大场镇");
        register("310113109", "顾村镇");
        register("310113112", "庙行镇");
    }

    private SupportedRegions() {}

    static Region require(String code) {
        Region region = REGIONS.get(normalize(code));
        if (region == null) throw new BusinessException(403, "当前地区尚未开放此功能");
        return region;
    }

    static boolean contains(String code) {
        return REGIONS.containsKey(normalize(code));
    }

    static String normalize(String code) {
        return code == null || code.isBlank() ? DEFAULT_CODE : code.trim();
    }

    static Optional<Region> mentionedIn(String text) {
        if (text == null) return Optional.empty();
        return REGIONS.values().stream().filter(region -> text.contains(region.townName())).findFirst();
    }

    private static void register(String code, String townName) {
        REGIONS.put(code, new Region(code, "宝山区", townName));
    }

    record Region(String code, String district, String townName) {}
}

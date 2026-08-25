package cn.jianda.publicapi;

import java.util.List;

/**
 * Published-content visibility rules shared by every resident-facing query.
 * UNCLASSIFIED/legacy UNSPECIFIED content is deliberately excluded whenever a
 * region is selected; explicit administrative evidence is required for shared
 * scopes so a district article cannot leak into another district.
 */
final class PublishedRegionScope {
    private PublishedRegionScope() {}

    static String predicate(String alias) {
        String p = alias == null || alias.isBlank() ? "p" : alias;
        return "((" + p + ".region_code=? AND " + p + ".local_scope IN ('LOCAL_TOWN','TOWN','STREET','LOCAL')) "
                + "OR (" + p + ".local_scope='DISTRICT_SHARED' AND " + p + ".district='宝山区' "
                + "AND (" + p + ".street_or_town IS NULL OR " + p + ".street_or_town='')) "
                + "OR (" + p + ".local_scope IN ('CITY_SHARED','CITY') AND " + p + ".city='上海市' "
                + "AND (" + p + ".district IS NULL OR " + p + ".district='')) "
                + "OR (" + p + ".local_scope IN ('NATIONAL_SHARED','NATIONAL') "
                + "AND (" + p + ".province IS NULL OR " + p + ".province='' OR " + p + ".province='全国')))";
    }

    static List<Object> parameters(String regionCode) {
        return List.of(SupportedRegions.require(regionCode).code());
    }
}

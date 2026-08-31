export interface H5UrlEnvironment {
  configuredBaseUrl?: string;
  isDev: boolean;
  protocol: string;
  hostname: string;
}

const supportedTownCodes = new Set(["310113102", "310113109", "310113112"]);

export function buildH5GuideUrl(
  publishedSlug: string,
  environment: H5UrlEnvironment,
  kind: "guide" | "news" = "guide",
  regionCode?: string,
): string {
  const configuredBaseUrl = environment.configuredBaseUrl?.trim();
  const baseUrl = configuredBaseUrl
    ? configuredBaseUrl
    : environment.isDev
      ? "http://127.0.0.1:5174"
      : `${environment.protocol}//${environment.hostname}`;

  const url = new URL(
    `/${kind}/${encodeURIComponent(publishedSlug)}`,
    baseUrl,
  );
  const normalizedRegionCode = regionCode?.trim() || "";
  if (supportedTownCodes.has(normalizedRegionCode)) {
    url.searchParams.set("region", normalizedRegionCode);
  }
  return url.toString();
}

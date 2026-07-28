export interface H5UrlEnvironment {
  configuredBaseUrl?: string;
  isDev: boolean;
  protocol: string;
  hostname: string;
}

export function buildH5GuideUrl(
  publishedSlug: string,
  environment: H5UrlEnvironment,
  kind: "guide" | "news" = "guide",
): string {
  const configuredBaseUrl = environment.configuredBaseUrl?.trim();
  const baseUrl = configuredBaseUrl
    ? configuredBaseUrl
    : environment.isDev
      ? "http://127.0.0.1:5174"
      : `${environment.protocol}//${environment.hostname}`;

  return new URL(
    `/${kind}/${encodeURIComponent(publishedSlug)}`,
    baseUrl,
  ).toString();
}

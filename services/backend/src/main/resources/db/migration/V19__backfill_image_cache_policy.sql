UPDATE source_registry
SET image_cache_allowed = TRUE
WHERE allow_image_cache = TRUE
  AND image_cache_allowed = FALSE;

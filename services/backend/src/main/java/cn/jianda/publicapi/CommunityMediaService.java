package cn.jianda.publicapi;

import cn.jianda.common.BusinessException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CommunityMediaService {
    public static final int MAX_FILES = 6;
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_DIMENSION = 10_000;
    private static final Set<String> ALLOWED_MIMES = Set.of("image/jpeg", "image/png");
    private final JdbcTemplate jdbc;
    private final Path mediaRoot;

    public CommunityMediaService(JdbcTemplate jdbc, @Value("${jianda.upload-dir}") String uploadDir) throws IOException {
        this.jdbc = jdbc;
        this.mediaRoot = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("community-media");
        Files.createDirectories(mediaRoot);
    }

    @Transactional
    public Map<String, Object> upload(Map<String, Object> resident, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BusinessException(400, "请选择图片");
        if (file.getSize() > MAX_BYTES) throw new BusinessException(400, "每张图片不能超过 8MB");
        long ownerId = ((Number) resident.get("id")).longValue();
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM community_post_media WHERE resident_user_id=? AND community_post_id IS NULL", Integer.class, ownerId);
        if (pending != null && pending >= MAX_FILES) throw new BusinessException(400, "每篇帖子最多上传 6 张图片");

        byte[] source = file.getBytes();
        String mime = detectedMime(source);
        if (!ALLOWED_MIMES.contains(mime)) throw new BusinessException(400, "仅支持 JPG 或 PNG 图片");
        ImageDimensions dimensions = dimensions(source);
        int width = dimensions.width();
        int height = dimensions.height();
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > (long) MAX_DIMENSION * MAX_DIMENSION) {
            throw new BusinessException(400, "图片尺寸不正确或过大");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new BusinessException(400, "图片内容无法识别");

        String extension = "image/png".equals(mime) ? "png" : "jpg";
        Path ownerDir = mediaRoot.resolve(String.valueOf(ownerId)).normalize();
        Files.createDirectories(ownerDir);
        String key = UUID.randomUUID().toString();
        Path stored = ownerDir.resolve(key + "." + extension).normalize();
        Path thumbnail = ownerDir.resolve(key + "-thumb." + extension).normalize();
        ensureInside(stored); ensureInside(thumbnail);
        writeWithoutMetadata(image, stored, extension);
        BufferedImage thumb = thumbnail(image, 480);
        writeWithoutMetadata(thumb, thumbnail, extension);
        long size = Files.size(stored);
        String hash = sha256(stored);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO community_post_media(resident_user_id,original_filename,mime_type,file_size,width,height,storage_path,thumbnail_path,sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, ownerId);
            statement.setString(2, safeName(file.getOriginalFilename()));
            statement.setString(3, mime);
            statement.setLong(4, size);
            statement.setInt(5, width);
            statement.setInt(6, height);
            statement.setString(7, stored.toString());
            statement.setString(8, thumbnail.toString());
            statement.setString(9, hash);
            return statement;
        }, keys);
        Number generatedId = keys.getKey();
        if (generatedId == null) {
            Files.deleteIfExists(stored);
            Files.deleteIfExists(thumbnail);
            throw new IllegalStateException("上传图片后未返回 ID");
        }
        long id = generatedId.longValue();
        return mediaDto(id, mime, width, height);
    }

    public List<Map<String, Object>> mediaForPosts(List<Map<String, Object>> posts) {
        for (Map<String, Object> post : posts) {
            long postId = ((Number) post.get("id")).longValue();
            post.put("media", jdbc.query("SELECT id,mime_type,width,height FROM community_post_media WHERE community_post_id=? ORDER BY id",
                    (rs, row) -> mediaDto(rs.getLong("id"), rs.getString("mime_type"), rs.getInt("width"), rs.getInt("height")), postId));
        }
        return posts;
    }

    @Transactional
    public void bind(long ownerId, long postId, List<Long> mediaIds) {
        List<Long> ids = mediaIds == null ? List.of() : mediaIds.stream().distinct().toList();
        if (ids.size() > MAX_FILES) throw new BusinessException(400, "每篇帖子最多 6 张图片");
        for (Long id : ids) {
            if (id == null || jdbc.update("UPDATE community_post_media SET community_post_id=?,bound_at=CURRENT_TIMESTAMP WHERE id=? AND resident_user_id=? AND community_post_id IS NULL", postId, id, ownerId) != 1) {
                throw new BusinessException(400, "图片不存在、已被使用或不属于当前居民");
            }
        }
    }

    public MediaFile load(long id, boolean thumbnail) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.storage_path,m.thumbnail_path,m.mime_type FROM community_post_media m JOIN community_post p ON p.id=m.community_post_id WHERE m.id=? AND p.status IN ('VISIBLE','REPORTED')", id);
        if (rows.isEmpty()) throw new BusinessException(404, "图片不存在或帖子已隐藏");
        Map<String, Object> row = rows.get(0);
        Path path = Paths.get(String.valueOf(row.get(thumbnail ? "thumbnail_path" : "storage_path"))).toAbsolutePath().normalize();
        if (!path.startsWith(mediaRoot) || !Files.isRegularFile(path)) throw new BusinessException(404, "图片文件不存在");
        return new MediaFile(new FileSystemResource(path), MediaType.parseMediaType(String.valueOf(row.get("mime_type"))));
    }

    private static Map<String, Object> mediaDto(long id, String mime, int width, int height) {
        return Map.of("id", id, "mimeType", mime, "width", width, "height", height,
                "url", "/api/public/community/media/" + id,
                "thumbnailUrl", "/api/public/community/media/" + id + "/thumbnail");
    }

    private void ensureInside(Path path) { if (!path.startsWith(mediaRoot)) throw new BusinessException(400, "图片路径不安全"); }
    private static String safeName(String name) { return Paths.get(name == null ? "image" : name).getFileName().toString().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_"); }
    private static String detectedMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0]&255)==0xff && (bytes[1]&255)==0xd8 && (bytes[2]&255)==0xff) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0]&255)==0x89 && bytes[1]==0x50 && bytes[2]==0x4e && bytes[3]==0x47 && bytes[4]==0x0d && bytes[5]==0x0a && bytes[6]==0x1a && bytes[7]==0x0a) return "image/png";
        return "";
    }
    private static ImageDimensions dimensions(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw new BusinessException(400, "图片内容无法识别");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(400, "图片内容无法识别");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage thumbnail(BufferedImage source, int max) {
        double scale = Math.min(1d, (double) max / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int)Math.round(source.getWidth()*scale)); int height = Math.max(1, (int)Math.round(source.getHeight()*scale));
        BufferedImage target = new BufferedImage(width, height, source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(source,0,0,width,height,null); graphics.dispose(); return target;
    }
    private static void writeWithoutMetadata(BufferedImage image, Path path, String format) throws IOException {
        if (!ImageIO.write(image, format, path.toFile())) throw new BusinessException(400, "当前服务无法处理该图片格式");
    }
    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[8192]; int count; while((count=input.read(buffer))!=-1) digest.update(buffer,0,count); return HexFormat.of().formatHex(digest.digest()); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record ImageDimensions(int width, int height) {}
    public record MediaFile(Resource resource, MediaType mediaType) {}
}

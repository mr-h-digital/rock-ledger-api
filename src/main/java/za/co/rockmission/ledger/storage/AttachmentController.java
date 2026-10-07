package za.co.rockmission.ledger.storage;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private final JdbcTemplate jdbc;
    private final FileStore files;

    public AttachmentController(JdbcTemplate jdbc, FileStore files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT object_key, file_name FROM attachments WHERE id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> attachment = rows.get(0);
        byte[] content = files.get((String) attachment.get("object_key"));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename((String) attachment.get("file_name"), StandardCharsets.UTF_8)
                .build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(content);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Principal who) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT transaction_id, object_key, file_name, content_type
            FROM attachments WHERE id = ? FOR UPDATE
            """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> attachment = rows.get(0);
        String key = (String) attachment.get("object_key");
        String name = (String) attachment.get("file_name");
        String contentType = (String) attachment.get("content_type");
        byte[] content = files.get(key);
        boolean removedFromBucket = false;
        try {
            files.delete(key);
            removedFromBucket = true;
            jdbc.update("DELETE FROM attachments WHERE id = ?", id);
            jdbc.update("INSERT INTO audit_log (actor, action, entity, entity_id, detail) VALUES (?,?,?,?,?)",
                who.getName(), "DELETE_DOCUMENT", "transaction", attachment.get("transaction_id"),
                name + " (attachment #" + id + ")");
        } catch (RuntimeException failure) {
            if (removedFromBucket) {
                try {
                    files.put(key, content, contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType);
                } catch (RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
    }
}

package za.co.rockmission.ledger.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Stores and retrieves files (receipts, statement PDFs) in the Railway bucket. */
@Service
public class FileStore {

    private final S3Client s3;
    private final String bucket;

    public FileStore(ObjectProvider<S3Client> s3, @Value("${ledger.storage.bucket:}") String bucket) {
        this.s3 = s3.getIfAvailable();
        this.bucket = bucket;
    }

    public boolean enabled() {
        return s3 != null && !bucket.isBlank();
    }

    public void put(String key, byte[] data, String contentType) {
        client().putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(data));
    }

    public byte[] get(String key) {
        return client().getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    public void delete(String key) {
        client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private S3Client client() {
        if (!enabled()) throw new IllegalStateException("File storage is not configured (set ENDPOINT, BUCKET, ACCESS_KEY_ID, SECRET_ACCESS_KEY).");
        return s3;
    }
}

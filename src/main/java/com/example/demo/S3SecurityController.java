package com.example.demo;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;

@RestController
@CrossOrigin(origins = "*") // 테스트를 위해 모든 오리진 허용
public class S3SecurityController {

    private final S3Client s3Client;
    private final String BUCKET_NAME;

    public S3SecurityController(S3Client s3Client, @Value("${cloud.aws.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.BUCKET_NAME = bucketName;
    }

    // 파일 업로드 API
    @PostMapping("/api/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        // 서버 디스크에 저장하지 않고 입력 스트림에서 바로 S3로 전송
        s3Client.putObject(putObjectRequest,
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        return ResponseEntity.ok("업로드 성공: " + fileName);
    }

    // 파일 미리보기 API (S3 주소 은폐)
    @GetMapping("/api/preview/{fileName}")
    public ResponseEntity<Resource> previewFile(@PathVariable String fileName) {
        // 1. S3 객체 가져오기 요청
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(fileName)
                .build();

        // 2. S3로부터 스트림을 직접 열기 (서버 디스크에 저장 X)
        ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getObjectRequest);
        GetObjectResponse response = s3Stream.response();

        // 3. 스프링 리소스로 변환하여 즉시 반환
        InputStreamResource resource = new InputStreamResource(s3Stream);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(resource);
    }
}

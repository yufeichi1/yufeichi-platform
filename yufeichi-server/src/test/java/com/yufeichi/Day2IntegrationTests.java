package com.yufeichi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yufeichi.server.YufeichiServerApplication;
import com.yufeichi.server.mapper.ArticleTagMapper;
import com.yufeichi.server.mapper.FileInfoMapper;
import com.yufeichi.server.entity.FileInfo;
import com.yufeichi.server.dto.FileUploadRequest;
import com.yufeichi.server.service.FileService;
import com.yufeichi.server.security.CustomUserDetailsService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=YufeichiServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.profiles.active=test")
@ActiveProfiles("test")
@Testcontainers

class Day2IntegrationTests {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("day2_test").withUsername("day2_test").withPassword("isolated-day2-test-password");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7")
            .withExposedPorts(6379).withCommand("redis-server","--requirepass","isolated-day2-redis-password");
    @TempDir static Path uploadRoot;
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);
        r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password",()->"isolated-day2-redis-password");
        r.add("file.upload-path",()->uploadRoot.toString());
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean ArticleTagMapper relations;
    @MockitoSpyBean FileInfoMapper fileMapper;
    @Autowired FileService fileService;
    @Autowired CustomUserDetailsService users;
    @Autowired PlatformTransactionManager transactions;
    private static String admin;
    private static String reader;

    @BeforeEach void accounts() {
        if (admin != null) return;
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("day2_test");
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=1");
        admin=login("admin","Admin@123456");
        jdbc.update("INSERT INTO sys_user(id,username,password,status) VALUES(100,'day2-reader',?,1)",passwords.encode("reader-test-password"));
        jdbc.update("INSERT INTO sys_role(id,role_code,role_name,status) VALUES(100,'day2_reader','Reader',1)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(100,100)");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT 100,id FROM sys_permission WHERE permission_code IN ('article:list','category:list','tag:list','project:list')");
        reader=login("day2-reader","reader-test-password");
    }
    @AfterAll static void stopRedis(@Autowired LettuceConnectionFactory factory) { factory.stop(); }

    @Test void taxonomyCrudConflictsAndVisibility() {
        String name=unique("cat");
        var payload=Map.of("name",name,"slug",name,"description","description");
        long id=ok(call(HttpMethod.POST,"/api/admin/categories",payload,admin)).path("id").asLong();
        status(call(HttpMethod.POST,"/api/admin/categories",payload,admin),409);
        status(call(HttpMethod.PUT,"/api/admin/categories/"+id,Map.of("name",name,"slug",name,"status",0),admin),200);
        assertThat(ok(call(HttpMethod.GET,"/api/categories",null,null)).toString()).doesNotContain(name);
        status(call(HttpMethod.DELETE,"/api/admin/categories/"+id,null,admin),200);
        status(call(HttpMethod.POST,"/api/admin/categories",payload,admin),409); // Soft deletion does not release unique keys.
        status(call(HttpMethod.PUT,"/api/admin/categories/"+id,payload,admin),404);
        long tag=tag();
        String tagName=unique("tag");
        status(call(HttpMethod.PUT,"/api/admin/tags/"+tag,Map.of("name",tagName,"slug",tagName,"status",0),admin),200);
        assertThat(ok(call(HttpMethod.GET,"/api/tags",null,null)).toString()).doesNotContain(tagName);
        status(call(HttpMethod.DELETE,"/api/admin/tags/"+tag,null,admin),200);
    }

    @Test void articleLifecycleRelationsAndPublicFiltering() {
        long category=category(),tag=tag();
        var body=articleBody(category,List.of(tag,tag));
        body.put("authorId",100); body.put("status",1); // Cannot spoof ownership or bypass publish permission.
        var article=ok(call(HttpMethod.POST,"/api/admin/articles",body,admin));
        long id=article.path("id").asLong();
        assertThat(article.path("authorId").asLong()).isEqualTo(1);
        assertThat(article.path("status").asInt()).isZero();
        assertThat(article.path("tagIds").size()).isEqualTo(1);
        status(call(HttpMethod.GET,"/api/articles/"+id,null,null),404);
        assertThat(ok(call(HttpMethod.GET,"/api/articles?categoryId="+category+"&status=0",null,null)).path("total").asLong()).isZero();
        status(call(HttpMethod.DELETE,"/api/admin/categories/"+category,null,admin),409);
        status(call(HttpMethod.DELETE,"/api/admin/tags/"+tag,null,admin),409);
        status(call(HttpMethod.POST,"/api/admin/articles/"+id+"/publish",null,admin),200);
        assertThat(ok(call(HttpMethod.GET,"/api/articles/"+id,null,null)).path("content").asText()).isEqualTo("# Content");
        var list=ok(call(HttpMethod.GET,"/api/articles?tagId="+tag+"&pageSize=1",null,null));
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("records").get(0).has("content")).isFalse();
        status(call(HttpMethod.POST,"/api/admin/articles/"+id+"/unpublish",null,admin),200);
        status(call(HttpMethod.GET,"/api/articles/"+id,null,null),404);
        var changed=articleBody(null,List.of()); changed.put("summary",null); changed.put("coverUrl",null);
        status(call(HttpMethod.PUT,"/api/admin/articles/"+id,changed,admin),200);
        assertThat(jdbc.queryForObject("SELECT category_id FROM blog_article WHERE id=?",Long.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_article_tag WHERE article_id=?",Long.class,id)).isZero();
        status(call(HttpMethod.DELETE,"/api/admin/categories/"+category,null,admin),200);
        status(call(HttpMethod.DELETE,"/api/admin/tags/"+tag,null,admin),200);
        status(call(HttpMethod.DELETE,"/api/admin/articles/"+id,null,admin),200);
        status(call(HttpMethod.GET,"/api/admin/articles/"+id,null,admin),404);
        status(call(HttpMethod.GET,"/api/articles/"+id,null,null),404);
        assertThat(jdbc.queryForObject("SELECT deleted FROM blog_article WHERE id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void transactionRollsBackArticleAndRelationsOnDatabaseWriteFailure() {
        long first=tag(),second=tag();
        var original=articleBody(null,List.of(first));
        long id=ok(call(HttpMethod.POST,"/api/admin/articles",original,admin)).path("id").asLong();
        var changed=articleBody(null,List.of(second));
        changed.put("title","must-roll-back");
        doThrow(new DataAccessResourceFailureException("injected relationship write failure"))
                .when(relations).insertTags(anyLong(),anyList());
        try {
            status(call(HttpMethod.PUT,"/api/admin/articles/"+id,changed,admin),500);
            assertThat(jdbc.queryForObject("SELECT title FROM blog_article WHERE id=?",String.class,id)).isEqualTo(original.get("title"));
            assertThat(jdbc.queryForList("SELECT tag_id FROM blog_article_tag WHERE article_id=?",Long.class,id)).containsExactly(first);
            status(call(HttpMethod.POST,"/api/admin/articles",changed,admin),500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_article WHERE title='must-roll-back'",Long.class)).isZero();
        } finally { reset(relations); }
    }

    @Test void rejectsInvalidReferencesAndRequestBoundaries() {
        var body=articleBody(null,List.of(999999L));
        status(call(HttpMethod.POST,"/api/admin/articles",body,admin),400);
        body=articleBody(999999L,List.of());
        status(call(HttpMethod.POST,"/api/admin/articles",body,admin),400);
        long disabled=tag(); jdbc.update("UPDATE blog_tag SET status=0 WHERE id=?",disabled);
        status(call(HttpMethod.POST,"/api/admin/articles",articleBody(null,List.of(disabled)),admin),400);
        for(String query:List.of("pageNum=0","pageSize=101","status=3","categoryId=-1","tagId=0","pageNum=abc"))
            status(call(HttpMethod.GET,"/api/admin/articles?"+query,null,admin),400);
        status(call(HttpMethod.GET,"/api/articles/0",null,null),400);
        status(call(HttpMethod.POST,"/api/admin/articles",Map.of("title","x".repeat(201),"content","content"),admin),400);
        status(call(HttpMethod.POST,"/api/admin/categories",Map.of("name","cat","slug","../bad"),admin),400);
        status(call(HttpMethod.POST,"/api/admin/tags",Map.of("name","cat","slug","cat","status",2),admin),400);
    }

    @Test void readerCanReadButCannotWriteAndAnonymousCannotAdmin() {
        for(String type:List.of("articles","categories","tags")) {
            status(call(HttpMethod.GET,"/api/admin/"+type,null,null),401);
            status(call(HttpMethod.GET,"/api/admin/"+type,null,reader),200);
            Object body=type.equals("articles") ? articleBody(null,List.of()) : Map.of("name","valid","slug","valid");
            status(call(HttpMethod.POST,"/api/admin/"+type,body,null),401);
            status(call(HttpMethod.PUT,"/api/admin/"+type+"/99999",body,null),401);
            status(call(HttpMethod.DELETE,"/api/admin/"+type+"/99999",null,null),401);
            status(call(HttpMethod.POST,"/api/admin/"+type,body,reader),403);
            status(call(HttpMethod.PUT,"/api/admin/"+type+"/99999",body,reader),403);
            status(call(HttpMethod.DELETE,"/api/admin/"+type+"/99999",null,reader),403);
        }
        status(call(HttpMethod.POST,"/api/admin/articles/99999/publish",null,reader),403);
        status(call(HttpMethod.POST,"/api/admin/articles/99999/unpublish",null,reader),403);
        status(call(HttpMethod.POST,"/api/admin/articles/99999/publish",null,null),401);
        status(call(HttpMethod.POST,"/api/admin/articles/99999/unpublish",null,null),401);
    }

    @Test void projectCrudAndHiddenVisibility() {
        String name=unique("project");
        var body=new HashMap<String,Object>(); body.put("name",name); body.put("description","Project description");
        body.put("githubUrl","https://github.com/example/project");
        long id=ok(call(HttpMethod.POST,"/api/admin/projects",body,admin)).path("id").asLong();
        status(call(HttpMethod.GET,"/api/projects/"+id,null,null),404);
        assertThat(ok(call(HttpMethod.GET,"/api/projects?keyword="+name+"&status=0",null,null)).path("total").asLong()).isZero();
        status(call(HttpMethod.PUT,"/api/admin/projects/"+id+"/status",Map.of("status",1),admin),200);
        status(call(HttpMethod.GET,"/api/projects/"+id,null,null),200);
        assertThat(ok(call(HttpMethod.GET,"/api/projects?keyword="+name,null,null)).path("total").asLong()).isEqualTo(1);
        body.put("name",name+"-edit"); body.put("githubUrl",null); body.put("status",1);
        status(call(HttpMethod.PUT,"/api/admin/projects/"+id,body,admin),200);
        assertThat(jdbc.queryForObject("SELECT github_url FROM project WHERE id=?",String.class,id)).isNull();
        status(call(HttpMethod.PUT,"/api/admin/projects/"+id+"/status",Map.of("status",0),admin),200);
        status(call(HttpMethod.GET,"/api/projects/"+id,null,null),404);
        status(call(HttpMethod.DELETE,"/api/admin/projects/"+id,null,admin),200);
        status(call(HttpMethod.GET,"/api/admin/projects/"+id,null,admin),404);
        status(call(HttpMethod.GET,"/api/projects/"+id,null,null),404);
        assertThat(jdbc.queryForObject("SELECT deleted FROM project WHERE id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void projectPermissionsAndValidation() {
        var body=Map.of("name","project","description","description");
        status(call(HttpMethod.GET,"/api/admin/projects",null,null),401);
        status(call(HttpMethod.GET,"/api/admin/projects",null,reader),200);
        status(call(HttpMethod.POST,"/api/admin/projects",body,reader),403);
        status(call(HttpMethod.PUT,"/api/admin/projects/1",body,reader),403);
        status(call(HttpMethod.PUT,"/api/admin/projects/1/status",Map.of("status",1),reader),403);
        status(call(HttpMethod.DELETE,"/api/admin/projects/1",null,reader),403);
        status(call(HttpMethod.POST,"/api/admin/projects",body,null),401);
        status(call(HttpMethod.PUT,"/api/admin/projects/1",body,null),401);
        status(call(HttpMethod.PUT,"/api/admin/projects/1/status",Map.of("status",1),null),401);
        status(call(HttpMethod.DELETE,"/api/admin/projects/1",null,null),401);
        status(call(HttpMethod.GET,"/api/projects?pageSize=101",null,null),400);
        status(call(HttpMethod.GET,"/api/projects?status=2",null,null),400);
        status(call(HttpMethod.POST,"/api/admin/projects",Map.of("name","valid","description","valid","demoUrl","javascript:alert(1)"),admin),400);
        status(call(HttpMethod.PUT,"/api/admin/projects/1/status",Map.of("status",2),admin),400);
    }

    @Test void uploadRealPngJpegAndWebpThenReadPublicly() throws Exception {
        for(String format:List.of("png","jpg","webp")) {
            byte[] bytes=format.equals("webp") ? getClass().getResourceAsStream("/fixtures/sample.webp").readAllBytes() : image(format);
            var data=ok(upload("image."+format,bytes,"article",admin));
            String url=data.path("fileUrl").asText();
            assertThat(url).matches("/uploads/article/[a-f0-9-]+\\.(png|jpg)");
            assertThat(data.has("filePath")).isFalse();
            assertThat(data.toString()).doesNotContain(uploadRoot.toString());
            var response=http.getForEntity(url,byte[].class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getContentType().toString()).startsWith("image/");
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(response.getBody())).getWidth()).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT uploader_id FROM file_info WHERE id=?",Long.class,data.path("id").asLong())).isEqualTo(1);
        }
    }

    @Test void uploadRejectsMaliciousFilesPathsAndUnauthorizedRequests() throws Exception {
        byte[] png=image("png");
        status(upload("image.png",png,"article",null),401);
        status(upload("image.png",png,"article",reader),403);
        status(upload("image.svg","<svg onload='alert(1)'/>".getBytes(),"article",admin),400);
        status(upload("image.png","<html>not an image</html>".getBytes(),"article",admin),400);
        status(upload("image.jpg",png,"article",admin),400);
        status(upload("image.png",new byte[0],"article",admin),400);
        status(upload("image.png",png,"../../outside",admin),400);
        status(upload("../escape.png",png,"article",admin),400);
        status(upload("image.png",new byte[5*1024*1024+1],"article",admin),413);
        status(call(HttpMethod.POST,"/api/admin/files/upload",Map.of(),admin),415);
        for(String path:List.of("/uploads/article/no-file.png","/uploads/article/.upload-secret.tmp","/uploads/other/application.yml"))
            assertThat(http.getForEntity(path,String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.getForEntity("/uploads/article/%2e%2e/%2e%2e/application.yml",String.class).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test void uploadDatabaseFailureCompensatesTemporaryFiles() throws Exception {
        long before=countStoredFiles();
        doThrow(new DataAccessResourceFailureException("injected file metadata failure")).when(fileMapper).insert(any(FileInfo.class));
        try { status(upload("image.png",image("png"),"project",admin),500); }
        finally { reset(fileMapper); }
        assertThat(countStoredFiles()).isEqualTo(before);
    }

    @Test void rollbackAfterFileMoveRemovesMetadataAndPublishedFile() throws Exception {
        long before=countStoredFiles();
        var request=new FileUploadRequest(); request.setBizType("other");
        request.setFile(new MockMultipartFile("file","rollback.png","image/png",image("png")));
        var user=users.loadUserById(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
        final long[] fileId={0};
        try {
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
                try { fileId[0]=fileService.upload(request).id(); }
                catch(Exception e) { throw new IllegalStateException(e); }
                throw new IllegalStateException("rollback after move");
            })).isInstanceOf(IllegalStateException.class).hasMessage("rollback after move");
        } finally { SecurityContextHolder.clearContext(); }
        assertThat(fileId[0]).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_info WHERE id=?",Long.class,fileId[0])).isZero();
        assertThat(countStoredFiles()).isEqualTo(before);
    }

    @Test void uploadedImageSurvivesApplicationRestart() throws Exception {
        String url=ok(upload("restart.png",image("png"),"other",admin)).path("fileUrl").asText();
        Map<String,Object> settings=new HashMap<>();
        settings.put("spring.profiles.active","test"); settings.put("server.port",0);
        settings.put("spring.datasource.url",MYSQL.getJdbcUrl()); settings.put("spring.datasource.username",MYSQL.getUsername());
        settings.put("spring.datasource.password",MYSQL.getPassword()); settings.put("spring.data.redis.host",REDIS.getHost());
        settings.put("spring.data.redis.port",REDIS.getMappedPort(6379)); settings.put("spring.data.redis.password","isolated-day2-redis-password");
        settings.put("file.upload-path",uploadRoot.toString());
        // Start, stop, then start a fresh real HTTP server against the same isolated disk/DB.
        // Command-line properties override mandatory TEST_* placeholders; no dev properties are used.
        String[] args=settings.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
        for(int restart=0;restart<2;restart++) {
            try(var context=new SpringApplicationBuilder(YufeichiServerApplication.class).run(args)) {
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
                int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
                var result=http.getForEntity("http://127.0.0.1:"+port+url,byte[].class);
                assertThat(result.getStatusCode().value()).isEqualTo(200);
                assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.getBody())).getWidth()).isEqualTo(8);
            }
        }
    }

    private byte[] image(String format) throws Exception {
        var output=new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(8,6,BufferedImage.TYPE_INT_RGB),format,output)).isTrue();
        return output.toByteArray();
    }
    private long countStoredFiles() throws Exception {
        try(var stream=Files.walk(uploadRoot)) { return stream.filter(Files::isRegularFile).count(); }
    }
    private ResponseEntity<JsonNode> upload(String name,byte[] data,String business,String token) {
        var body=new LinkedMultiValueMap<String,Object>();
        body.add("file",new ByteArrayResource(data) { @Override public String getFilename() { return name; } });
        body.add("bizType",business);
        var headers=new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if(token!=null) headers.setBearerAuth(token);
        return http.exchange("/api/admin/files/upload",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    }

    @Test void auditRecordsLoginPublishDeleteUploadWithoutCredentialsOrBodies() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            String token=login("admin","Admin@123456");
            var body=articleBody(null,List.of());
            body.put("content","private-audit-body-never-log");
            long id=ok(call(HttpMethod.POST,"/api/admin/articles",body,token)).path("id").asLong();
            status(call(HttpMethod.POST,"/api/admin/articles/"+id+"/publish",null,token),200);
            status(call(HttpMethod.DELETE,"/api/admin/articles/"+id,null,token),200);
            ok(upload("private-audit-name.png",image("png"),"other",token));
            String logs=events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("audit action=login outcome=success", "audit action=article.publish-state outcome=success",
                    "audit action=article.delete outcome=success", "audit action=file.upload outcome=success");
            assertThat(logs).doesNotContain(token,"Authorization","Admin@123456","private-audit-body-never-log","private-audit-name.png");
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test void openApiDescribesAuthenticationAndPaginationForDay2() {
        var response=http.getForEntity("/v3/api-docs",JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode paths=response.getBody().path("paths");
        for(String path:List.of("/api/admin/articles","/api/admin/categories","/api/admin/tags","/api/admin/projects","/api/admin/files/upload"))
            assertThat(paths.path(path).path("post").path("security").toString()).contains("BearerAuth");
        assertThat(paths.path("/api/articles").path("get").path("parameters").toString()).contains("pageNum","pageSize","categoryId","tagId");
        assertThat(paths.path("/api/admin/files/upload").path("post").path("requestBody").path("content").has("multipart/form-data")).isTrue();
    }

    private String unique(String prefix) { return prefix+"-"+UUID.randomUUID(); }
    private long category() {
        String name=unique("cat");
        return ok(call(HttpMethod.POST,"/api/admin/categories",Map.of("name",name,"slug",name),admin)).path("id").asLong();
    }
    private long tag() {
        String name=unique("tag");
        return ok(call(HttpMethod.POST,"/api/admin/tags",Map.of("name",name,"slug",name),admin)).path("id").asLong();
    }
    private Map<String,Object> articleBody(Long category,List<Long> tags) {
        Map<String,Object> body=new HashMap<>();
        body.put("title",unique("article")); body.put("content","# Content"); body.put("categoryId",category); body.put("tagIds",tags);
        return body;
    }
    private String login(String username,String password) {
        return ok(call(HttpMethod.POST,"/api/auth/login",Map.of("username",username,"password",password),null)).path("token").asText();
    }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token) {
        var headers=new HttpHeaders();
        if(token!=null) headers.setBearerAuth(token);
        if(body!=null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private JsonNode ok(ResponseEntity<JsonNode> response) {
        status(response,200); assertThat(response.getBody().path("code").asInt()).isZero();
        return response.getBody().path("data");
    }
    private void status(ResponseEntity<JsonNode> response,int expected) {
        assertThat(response.getStatusCode().value()).as("HTTP status; response=%s",response.getBody()).isEqualTo(expected);
    }
}

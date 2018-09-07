package ua.net.tokar.json.filters.sample;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.Map;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith( SpringRunner.class )
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class ApplicationApiBatchTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void initialTest() throws Exception {
        Map<String, String> requestData = new HashMap<>();
        requestData.put( "group", "/groups" );
        requestData.put( "users", "/groups/2/users" );

        HttpEntity<Map> request = new HttpEntity<>( requestData );
        String body = restTemplate.postForObject( "/batch", request, String.class );

        assertThat( body, hasJsonPath( "$.group[0].id", is( 2 ) ) );
        assertThat( body, hasJsonPath( "$.group[0].title", is( "bad group" ) ) );
        assertThat( body, hasJsonPath( "$.group[0].users.href", is( "/groups/2/users" ) ) );

        assertThat( body, hasJsonPath( "$.users[0].name", is( "boss" ) ) );
        assertThat( body, hasJsonPath( "$.users[1].name", is( "cat" ) ) );

    }

    @Test
    public void badRequestTest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/batch",
                "incorrectBody",
                String.class
        );
        assertThat( response.getStatusCode(), is( HttpStatus.BAD_REQUEST ) );
    }
}

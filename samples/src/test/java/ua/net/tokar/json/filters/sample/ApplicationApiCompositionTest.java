package ua.net.tokar.json.filters.sample;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasNoJsonPath;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith( SpringRunner.class )
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class ApplicationApiCompositionTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void initialTest() throws Exception {
        String body = restTemplate.getForObject( "/groups", String.class );

        assertThat( body, hasJsonPath( "$[0].id", is( 2 ) ) );
        assertThat( body, hasJsonPath( "$[0].title", is( "bad group" ) ) );
        assertThat( body, hasJsonPath( "$[0].users.href", is( "/groups/2/users" ) ) );

        assertThat( body, hasJsonPath( "$[1].id", is( 5 ) ) );
        assertThat( body, hasJsonPath( "$[1].title", is( "good group" ) ) );
        assertThat( body, hasJsonPath( "$[1].users.href", is( "/groups/5/users" ) ) );

    }

    @Test
    public void fieldsTest() throws Exception {
        String body = restTemplate.getForObject( "/groups?fields=id", String.class );

        assertThat( body, hasJsonPath( "$[0].id", is( 2 ) ) );
        assertThat( body, hasNoJsonPath( "$[0].title" ) );
        assertThat( body, hasNoJsonPath( "$[0].users" ) );
    }

    @Test
    public void includeTest() throws Exception {
        String body = restTemplate.getForObject( "/groups?include=users", String.class );

        assertThat( body, hasJsonPath( "$[0].id", is( 2 ) ) );
        assertThat( body, hasJsonPath( "$[0].title", is( "bad group" ) ) );
        assertThat( body, hasJsonPath( "$[0].users[0].name", is( "boss" ) ) );
        assertThat( body, hasJsonPath( "$[0].users[1].name", is( "cat" ) ) );
        assertThat( body, hasNoJsonPath( "$[0].users.href" ) );
    }
}

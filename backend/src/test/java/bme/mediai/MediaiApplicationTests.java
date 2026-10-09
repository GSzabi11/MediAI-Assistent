package bme.mediai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "mediai.ai-service.token=test-only-token")
class MediaiApplicationTests {

	@Test
	void contextLoads() {
	}

}

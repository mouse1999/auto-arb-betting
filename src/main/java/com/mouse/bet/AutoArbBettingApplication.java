package com.mouse.bet;

import com.google.gson.Gson;
import com.microsoft.playwright.BrowserContext;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.tasks.Bet9jaOddsFetcher;
import com.mouse.bet.tasks.SportyBetOddsFetcher;
import com.mouse.bet.window.SportyWindow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class AutoArbBettingApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(AutoArbBettingApplication.class, args);
//		Bet9jaOddsFetcher fetcher = context.getBean(Bet9jaOddsFetcher.class);
//		SportyBetOddsFetcher fetcher = context.getBean(SportyBetOddsFetcher.class);

		SportyWindow window = context.getBean(SportyWindow.class);
		window.run();



//		fetcher.run();
	}



}

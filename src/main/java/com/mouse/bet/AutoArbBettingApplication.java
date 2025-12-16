package com.mouse.bet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class AutoArbBettingApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(AutoArbBettingApplication.class, args);
//		Bet9jaOddsFetcher fetcher = context.getBean(Bet9jaOddsFetcher.class);
//		SportyBetOddsFetcher fetcher = context.getBean(SportyBetOddsFetcher.class);

//		MSportWindow window = context.getBean(MSportWindow.class);
//		window.run();



//		fetcher.run();
	}



}

package com.demo.flowagent.fixtures;

import com.demo.flowagent.store.RecordStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时把 fixtures/records.json 种入 H2（已存在则跳过）。 */
@Component
public class FixtureSeeder implements ApplicationRunner {

    private final FixtureLoader fixtures;
    private final RecordStore records;

    public FixtureSeeder(FixtureLoader fixtures, RecordStore records) {
        this.fixtures = fixtures;
        this.records = records;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (FixtureLoader.RecordFixture fixture : fixtures.records()) {
            records.seedIfAbsent(fixture);
        }
    }
}

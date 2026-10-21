package com.demo.supplyagent.service;

import com.demo.supplyagent.web.ApiException;
import com.demo.supplyagent.mapper.AgentHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoResetServiceTest {
    @Test void explicitConfirmationIsRequiredBeforeAnyDatabaseAccess() {
        var db=mock(JdbcTemplate.class); var tx=mock(TransactionTemplate.class); var source=mock(DataSource.class);
        assertEquals(400,assertThrows(ApiException.class,()->new DemoResetService(db,tx,source,mock(AgentHistoryMapper.class)).reset(false,"admin")).getCode());
        verifyNoInteractions(db,tx,source);
    }
    @Test void remoteOrWrongDatabaseIsRefusedBeforeMarkerOrMutation() throws Exception {
        for(String url:new String[]{"jdbc:mysql://production.invalid:3306/supply_agent","jdbc:mysql://127.0.0.1:3306/company"}) {
            var db=mock(JdbcTemplate.class); var tx=mock(TransactionTemplate.class); var source=source(url);
            assertEquals(403,assertThrows(ApiException.class,()->new DemoResetService(db,tx,source,mock(AgentHistoryMapper.class)).reset(true,"admin")).getCode());
            verifyNoInteractions(db,tx);
        }
    }
    @Test void missingOrWrongMarkerNeverEntersMutationTransaction() throws Exception {
        var db=mock(JdbcTemplate.class); var tx=mock(TransactionTemplate.class);
        when(db.queryForObject("SELECT marker FROM demo_fixture_guard WHERE id=1",String.class)).thenReturn("wrong");
        assertEquals(409,assertThrows(ApiException.class,()->new DemoResetService(db,tx,source("jdbc:mysql://127.0.0.1:13306/supply_agent"),mock(AgentHistoryMapper.class)).reset(true,"admin")).getCode());
        verifyNoInteractions(tx);
    }
    private DataSource source(String url) throws Exception {
        var source=mock(DataSource.class); var connection=mock(Connection.class); var metadata=mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getMetaData()).thenReturn(metadata); when(metadata.getURL()).thenReturn(url);
        return source;
    }
}

package com.yuewei.plm.module.marketwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.marketwatch.MarketWatchTypes.UpdateItem;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MarketWatchServiceTest {
    private MarketWatchClient client;
    private JdbcTemplate jdbc;
    private MarketWatchService service;

    @BeforeEach
    void setUp() {
        client = mock(MarketWatchClient.class);
        jdbc = mock(JdbcTemplate.class);
        when(client.enabled()).thenReturn(true);
        service = new MarketWatchService(client,jdbc);
    }

    @AfterEach
    void clearActor() {
        CurrentUserContext.clear();
    }

    @Test
    void rejectsAnonymousAndDisabledAccessBeforeCallingPython() {
        assertThatThrownBy(service::health)
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.UNAUTHORIZED));

        CurrentUserContext.set(new CurrentUser(7L, "reader", "只读用户", false));
        when(client.enabled()).thenReturn(false);
        assertThatThrownBy(service::health)
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.RESOURCE_NOT_FOUND));
        verify(client, never()).health();
    }

    @Test
    void onlyMarketAssignmentOrAdministratorCanMaintainItems() {
        UpdateItem update = new UpdateItem("sampled", "重点", null);
        CurrentUserContext.set(new CurrentUser(7L, "reader", "只读用户", false));
        doReturn(user("reader", "只读用户", false)).when(jdbc).queryForList(anyString(), (Object[]) any());
        when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.update("phone", 9L, update))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.FORBIDDEN));
        verify(client, never()).update(anyString(), anyLong(), any());

        CurrentUserContext.set(new CurrentUser(1L, "admin", "管理员", true));
        doReturn(user("admin", "管理员", true)).when(jdbc).queryForList(anyString(), (Object[]) any());
        when(client.update("phone", 9L, Map.of("status", "sampled", "badge", "重点")))
            .thenReturn(Map.of("id", 9));
        assertThat(service.update("phone", 9L, update)).containsEntry("id", 9);
        verify(client).update("phone", 9L, Map.of("status", "sampled", "badge", "重点"));
    }

    @Test
    void marketAssignmentReceivesWritableConfiguration() {
        CurrentUserContext.set(new CurrentUser(8L, "market", "市场负责人", false));
        doReturn(user("market", "市场负责人", false)).when(jdbc).queryForList(anyString(), (Object[]) any());
        when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(1L);

        assertThat(service.config()).containsEntry("enabled", true).containsEntry("writable", true);
    }

    private static java.util.List<Map<String,Object>> user(String username, String displayName, boolean administrator) {
        return java.util.List.of(Map.of(
            "username", username,
            "display_name", displayName,
            "all_permissions", administrator
        ));
    }

}

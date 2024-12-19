package io.hhplus.tdd.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PointController.class)
class PointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PointService pointService;

    /*
     * point 조회 테스트
     */
    @Test
    @DisplayName("포인트 조회 성공")
    void point_Success() throws Exception {
        // given
        Long userId = 1L;
        UserPoint expectedPoint = new UserPoint(userId, 10000L, System.currentTimeMillis());
        given(pointService.select(userId)).willReturn(expectedPoint);

        // when & then
        mockMvc.perform(get("/point/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.point").value(10000));

        verify(pointService).select(userId);
    }

    /*
     * history 조회 테스트
     */
    @Test
    @DisplayName("포인트 이력 조회 성공")
    void history_Success() throws Exception {
        // given
        Long userId = 1L;
        List<PointHistory> histories = List.of(
                new PointHistory(1L, userId, 10000L, TransactionType.CHARGE, System.currentTimeMillis()),
                new PointHistory(2L, userId, 5000L, TransactionType.USE, System.currentTimeMillis())
        );
        given(pointService.getPointHistories(userId)).willReturn(histories);

        // when & then
        mockMvc.perform(get("/point/{id}/histories", userId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CHARGE"))
                .andExpect(jsonPath("$[1].type").value("USE"))
                .andExpect(jsonPath("$[0].amount").value(10000))
                .andExpect(jsonPath("$[1].amount").value(5000));

        verify(pointService).getPointHistories(userId);
    }

    /*
     * charge 테스트
     */
    @Test
    @DisplayName("포인트 충전 성공")
    void charge_Success() throws Exception {
        // given
        Long userId = 1L;
        Long amount = 10000L;
        UserPoint expectedPoint = new UserPoint(userId, amount, System.currentTimeMillis());
        given(pointService.charge(userId, amount)).willReturn(expectedPoint);
        given(pointService.select(userId)).willReturn(expectedPoint);

        // when & then
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(amount)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point").value(amount));

        verify(pointService).charge(userId, amount);
    }

    @Test
    @DisplayName("포인트 충전 실패 - 잘못된 금액")
    void charge_Fail_InvalidAmount() throws Exception {
        // given
        Long userId = 1L;
        Long invalidAmount = 5000L;
        given(pointService.charge(userId, invalidAmount))
                .willThrow(new IllegalStateException("최소 10_000원 이상 충전 가능합니다"));

        // when & then
        mockMvc.perform(patch("/point/{id}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(invalidAmount)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("최소 10_000원 이상 충전 가능합니다"));
    }

    /*
     * use 테스트
     */
    @Test
    @DisplayName("포인트 사용 성공")
    void use_Success() throws Exception {
        // given
        Long userId = 1L;
        Long amount = 5000L;
        Long remainingPoint = 5000L;
        UserPoint expectedPoint = new UserPoint(userId, remainingPoint, System.currentTimeMillis());
        given(pointService.use(userId, amount)).willReturn(expectedPoint);
        given(pointService.select(userId)).willReturn(expectedPoint);

        // when & then
        mockMvc.perform(patch("/point/{id}/use", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(amount)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.point").value(remainingPoint));

        verify(pointService).use(userId, amount);
    }

    @Test
    @DisplayName("포인트 사용 실패 - 잔액 부족")
    void use_Fail_InsufficientBalance() throws Exception {
        // given
        Long userId = 1L;
        Long amount = 20000L;
        given(pointService.use(userId, amount))
                .willThrow(new IllegalStateException("잔고가 부족하여 사용이 불가능 합니다"));

        // when & then
        mockMvc.perform(patch("/point/{id}/use", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.valueOf(amount)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잔고가 부족하여 사용이 불가능 합니다"));
    }

}
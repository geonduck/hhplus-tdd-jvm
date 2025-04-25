package io.hhplus.tdd.point;

public record UserPoint(
        long id,
        long point,
        long updateMillis
) {

    private static final long MIN_CHARGE_AMOUNT = 10_000;
    private static final long MAX_CHARGE_AMOUNT = 100_000;
    private static final long MAX_TOTAL_POINT = 10_000_000L;

    public static UserPoint empty(long id) {
        return new UserPoint(id, 0, System.currentTimeMillis());
    }

    public long calculateChargeAmount(long amount) {
        validateCharge(amount);
        return point + amount;
    }

    public long calculateUseAmount(long amount) {
        validateUseAmount(amount);
        return point - amount;
    }

    private void validateCharge(long amount) {
        if (amount < MIN_CHARGE_AMOUNT) {
            throw new IllegalArgumentException("최소 " + MIN_CHARGE_AMOUNT + "원 이상 충전 가능합니다");
        }
        if (amount % MIN_CHARGE_AMOUNT != 0) {
            throw new IllegalArgumentException("충전은 " + MIN_CHARGE_AMOUNT + "원 단위로 가능 합니다");
        }
        if (amount > MAX_CHARGE_AMOUNT) {
            throw new IllegalArgumentException("충전은 " + MAX_CHARGE_AMOUNT + "원 이하로 가능 합니다");
        }
        validateTotalPoint(point + amount);
    }

    private void validateUseAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("사용금액은 0원 이상이여야 합니다");
        }
        if (point - amount < 0) {
            throw new IllegalArgumentException("잔고가 부족하여 사용이 불가능 합니다");
        }
    }

    private void validateTotalPoint(long newPoint) {
        if (newPoint > MAX_TOTAL_POINT) {
            throw new IllegalArgumentException("최대 " + MAX_TOTAL_POINT + "포인트까지 보유 가능합니다");
        }
    }

}

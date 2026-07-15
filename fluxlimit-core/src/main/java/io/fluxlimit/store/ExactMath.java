package io.fluxlimit.store;

import java.math.BigInteger;

/** Exact rational math for algorithm state. BigInteger only on overflow. */
final class ExactMath {

  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  private ExactMath() {}

  static long mulDivFloor(long a, long b, long c) {
    if (a == 0 || b <= Long.MAX_VALUE / a) {
      return a * b / c;
    }
    return clampToLong(big(a).multiply(big(b)).divide(big(c)));
  }

  static long mulDivCeil(long a, long b, long c) {
    if (a == 0 || b <= Long.MAX_VALUE / a) {
      long product = a * b;
      long quotient = product / c;
      return product % c == 0 ? quotient : quotient + 1;
    }
    BigInteger[] quotientAndRemainder = big(a).multiply(big(b)).divideAndRemainder(big(c));
    BigInteger quotient =
        quotientAndRemainder[1].signum() == 0
            ? quotientAndRemainder[0]
            : quotientAndRemainder[0].add(BigInteger.ONE);
    return clampToLong(quotient);
  }

  private static long clampToLong(BigInteger value) {
    return value.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : value.longValue();
  }

  private static BigInteger big(long value) {
    return BigInteger.valueOf(value);
  }
}

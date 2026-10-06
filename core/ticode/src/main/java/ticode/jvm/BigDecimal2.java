package ticode.jvm;


public class BigDecimal2 extends java.math.BigDecimal {
public Object 赋值_op(String 值) {
new java.math.BigDecimal(值);
return this;
}

public BigDecimal2 加_op(BigDecimal2 另一个大数字) {
return 加(另一个大数字);
}

public BigDecimal2 减_op(BigDecimal2 另一个大数字) {
return 减(另一个大数字);
}

public BigDecimal2 乘_op(BigDecimal2 另一个大数字) {
return 乘(另一个大数字);
}

public BigDecimal2 除_op(BigDecimal2 另一个大数字) {
return 除以(另一个大数字);
}




public BigDecimal2 加(BigDecimal2 另一个大数字) {
return this.add(另一个大数字);
}




public BigDecimal2 减(BigDecimal2 另一个大数字) {
return this.subtract(另一个大数字);
}




public BigDecimal2 乘(BigDecimal2 另一个大数字) {
return this.multiply(另一个大数字);
}




public BigDecimal2 除以(BigDecimal2 另一个大数字) {
return this.divide(另一个大数字);
}
}
package ticode.android;

import android.graphics.Path;
import android.graphics.RectF;

public class PathBuilder extends android.graphics.Path {
public static PathBuilder 创建路径() {
Path path = new Path();
return path;
}








public void 填充模式(int 模式) {
switch (模式) {
case 0:
this.setFillType(Path.FillType.WINDING);
case 1:
this.setFillType(Path.FillType.EVEN_ODD);
case 2:
this.setFillType(Path.FillType.INVERSE_EVEN_ODD);
case 3:
this.setFillType(Path.FillType.INVERSE_WINDING);
return;
this.setFillType(Path.FillType.WINDING);
}
}

// 返回的是 Path.FillType 对象
public Object 填充模式() {
return this.getFillType();
}

public void 起点开始(float x, float y) {
this.moveTo(x,y);
}

public void 连接下一个点(float x, float y) {
this.lineTo(x,y);
}

public void 正圆(float x, float y, float 半径, boolean 顺时针) {
this.addCircle(x,y,半径, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 椭圆(float x, float y, float 宽度, float 高度, boolean 顺时针) {
this.addOval(new RectF(x,y,宽度,高度), 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 正角矩形(float 起点x, float 起点y, float 终点x, float 终点y, boolean 顺时针) {
this.addRect(起点x,起点y,终点x,终点y, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

public void 圆角矩形(float 起点x, float 起点y, float 终点x, float 终点y, int 上圆角, int 下圆角, boolean 顺时针) {
this.addRoundRect(起点x,起点y,终点x,终点y,上圆角,下圆角, 顺时针 ? Path.Direction.CW : Path.Direction.CCW);
}

// 重点，坐标组例: 坐标组 = {x1,y1, x2,y2}，需要对应
public void 添加多边形(float[] 坐标组, int 起点偏移量, int 顶点数量, boolean 是否闭合) {
this.addPolygon(坐标组,起点偏移量,顶点数量,是否闭合);
}

// 不依赖当前路径，独立添加弧形
public void 添加弧形(float 左, float 上, float 右, float 下, float 起始角度, float 扫过角度) {
this.addArc(左,上,右,下,起始角度,扫过角度);
}

// 链接当前终点开始绘制弧形
public void 连接弧形(float 左, float 上, float 右, float 下, float 起始角度, float 扫过角度, boolean 抬起) {
this.arcTo(左,上,右,下,起始角度,扫过角度,抬起);
}

public void 闭合区域() {
this.close();
}

// 清空路径，保留内部，(复用性高)
public void 清空路径() {
this.reset();
}

// 清除路径轮廓，保留填充模式等参数
public void 重置路径() {
this.rewind();
}

public boolean 是否为空() {
return this.isEmpty();
}

public void 替换路径(PathBuilder 路径) {
this.set(路径);
}

public void 平移路径(float x, float y) {
this.offset(x,y);
}

public boolean 是否包含坐标(float x, float y) {
return this.contains(x,y);
}

// 取两个区域共同包含的区域
public PathBuilder 交集(PathBuilder 区域1, PathBuilder 区域2) {
Path resultPath = new Path();
Path.op(path1, path2, Path.Op.INTERSECT, resultPath);
return resultPath;
}

// 保留未被参数区域覆盖的区域
public boolean 差集(PathBuilder 区域) {
this.op(区域, Path.Op.DIFFERENCE);
}

// 保留被参数区域覆盖的区域
public boolean 反向差集(PathBuilder 区域) {
this.op(区域, Path.Op.REVERSE_DIFFERENCE);
}

// 保留非重叠，删除已重叠区域
public boolean 异或(PathBuilder 区域) {
this.op(区域, Path.Op.XOR);
}

// 将第二个区域，合并进第一个区域
public boolean 合并(PathBuilder 原区域, PathBuilder 新区域) {
return 原区域.op(新区域, Path.Op.UNION);
}

}
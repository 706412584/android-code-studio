package ticode.android;

import java.io.File;
import java.util.ArrayList;
import android.database.sqlite.SQLiteDatabase;

public class Database2 extends android.database.sqlite.SQLiteDatabase {





public static Database2 打开数据库(String 数据库路径) {
File file = new File(数据库路径);
File dirs = file.getParentFile();
if (!dirs.exists() && !dirs.mkdirs()) return null;
return SQLiteDatabase.openOrCreateDatabase(file, null);
}







public void 创建数据表(String 表名, String 结构) {
执行SQL语句("CREATE TABLE IF NOT EXISTS " + 表名 + "(" + 结构 + ")");
}






public void 执行SQL语句(String SQL语句) {
if (this == null) return;
this.execSQL(SQL语句);
}






public RecordSet 执行SQL查询语句(String SQL查询语句) {
return this.rawQuery(SQL查询语句, null);
}







public void 插入记录(String 表名, String 记录) {
执行SQL语句("INSERT INTO " + 表名 + " VALUES(" + 记录 + ")");
}







public void 删除记录(String 表名, String 条件) {
if (条件 == null || 条件.为空()) {
执行SQL语句("DELETE FROM " + 表名);
} else {
执行SQL语句("DELETE FROM " + 表名 + " WHERE " + 条件);
}
}








public void 更新记录(String 表名, String 记录, String 条件) {
if (条件 == null || 条件.为空()) {
执行SQL语句("UPDATE " + 表名 + " SET " + 记录);
} else {
执行SQL语句("UPDATE " + 表名 + " SET " + 记录 + " WHERE " + 条件);
}
}







public RecordSet 查询记录(String 表名, String 条件) {
if (条件 == null || 条件.为空()) {
return 执行SQL查询语句("SELECT * FROM " + 表名);
} else {
return 执行SQL查询语句("SELECT * FROM " + 表名 + " WHERE " + 条件);
}
}




public void 关闭数据库() {
if (this == null) return;
this.close();
}






public void 删除数据表(String 表名) {
执行SQL语句("DROP TABLE " + 表名);
}





public static void 删除数据库(String 数据库路径) {
File file = new File(数据库路径);
if (!file.exists()) return;
SQLiteDatabase.deleteDatabase(file);
}
}
package ticode.jvm;


public class JDeque extends DequeTemplate<Object> {

public static JDeque 从集合创建(JCollection 集合1) {
return new java.util.ArrayDeque(集合1);
}

}
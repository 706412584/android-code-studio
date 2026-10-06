package ticode.android;

import android.view.ViewGroup;

public abstract class CustomComponent extends LayoutComponent {
public CustomComponent(android.content.Context context) {
super(context);
}

@Override
public android.view.ViewGroup onCreateView(android.content.Context context) {
return onCreateComponent(context).getView();
}

@Override
public ViewGroup getView() {
return (ViewGroup) view;
}

protected LayoutComponent onCreateComponent(android.content.Context context) {
return new EmptyLayout(context);
}
}
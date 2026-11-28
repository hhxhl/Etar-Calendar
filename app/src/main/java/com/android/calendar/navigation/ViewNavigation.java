package com.android.calendar.navigation;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.PopupMenu;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.Utils;
import com.android.calendar.settings.GeneralPreferences;
import com.google.android.material.color.MaterialColors;
import java.util.function.IntConsumer;
import ws.xsoh.etar.R;

/** One definition of the five view destinations for the drawer and toolbar. */
public final class ViewNavigation {
    private ViewNavigation() {}

    private static final int[][] DESTINATIONS = {
            {R.id.day_menu_item, ViewType.DAY},
            {R.id.week_menu_item, ViewType.WEEK},
            {R.id.month_menu_item, ViewType.MONTH},
            {R.id.agenda_menu_item, ViewType.AGENDA},
            {R.id.tasks_menu_item, ViewType.TASKS}
    };

    public static boolean isTaskView(int view) {
        return view == ViewType.TASKS;
    }

    public static int viewForItem(int id) {
        for (int[] destination : DESTINATIONS)
            if (destination[0] == id) return destination[1];
        return -1;
    }

    public static MenuItem itemForView(Menu drawer, int view) {
        for (int[] destination : DESTINATIONS)
            if (destination[1] == view) return drawer.findItem(destination[0]);
        return null;
    }

    public static void select(Menu drawer, int view) {
        MenuItem selected = itemForView(drawer, view);
        if (selected == null) selected = drawer.findItem(R.id.week_menu_item);
        for (int[] destination : DESTINATIONS) {
            MenuItem item = drawer.findItem(destination[0]);
            if (item != null) item.setChecked(false);
        }
        if (selected != null) selected.setChecked(true);
    }

    private static Drawable icon(Context context, MenuItem item) {
        Drawable source = item.getIcon();
        if (source == null || source.getConstantState() == null) return null;
        Drawable copy = source.getConstantState().newDrawable(
                context.getResources(), context.getTheme()).mutate();
        copy.setTint(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorControlNormal, Color.BLACK));
        return copy;
    }

    public static void updateToolbar(Context context, Menu toolbar, Menu drawer, int currentView) {
        if (toolbar == null) return;
        MenuItem switchItem = toolbar.findItem(R.id.action_view_switch);
        MenuItem current = itemForView(drawer, currentView);
        boolean show = current != null && Utils.getSharedPreference(context,
                GeneralPreferences.KEY_VIEW_SWITCH_BUTTONS, false);
        if (switchItem != null) {
            switchItem.setVisible(show);
            if (show) {
                switchItem.setTitle(current.getTitle());
                if (switchItem.getActionView() instanceof AppCompatImageButton button) {
                    button.setImageDrawable(icon(context, current));
                    button.setContentDescription(current.getTitle());
                }
            }
        }
        MenuItem today = toolbar.findItem(R.id.action_today);
        if (today != null) {
            today.setVisible(true);
            today.setShowAsAction(show || isTaskView(currentView)
                    ? MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }

    public static void showSelection(Context context, View anchor, Menu drawer, int currentView,
            IntConsumer onSelected) {
        PopupMenu popup = new PopupMenu(context, anchor, Gravity.END);
        popup.setForceShowIcon(true);
        Menu menu = popup.getMenu();
        final int group = 1;
        for (int i = 0; i < DESTINATIONS.length; i++) {
            MenuItem source = drawer.findItem(DESTINATIONS[i][0]);
            if (source == null) continue;
            menu.add(group, source.getItemId(), i, source.getTitle()).setIcon(icon(context, source));
        }
        menu.setGroupCheckable(group, true, true);
        MenuItem current = itemForView(drawer, currentView);
        if (current != null) {
            MenuItem selected = menu.findItem(current.getItemId());
            if (selected != null) selected.setChecked(true);
        }
        popup.setOnMenuItemClickListener(item -> {
            onSelected.accept(viewForItem(item.getItemId()));
            return true;
        });
        popup.show();
    }

    public static void updateActions(Menu menu, int view) {
        if (menu == null) return;
        MenuItem goTo = menu.findItem(R.id.action_goto);
        if (goTo != null) goTo.setVisible(true);
        MenuItem search = menu.findItem(R.id.action_search);
        if (search != null) search.setVisible(true);
        MenuItem importItem = menu.findItem(R.id.action_import);
        if (importItem != null) importItem.setVisible(!isTaskView(view));
    }
}

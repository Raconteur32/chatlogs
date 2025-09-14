package fr.raconteur.chatlogs.util;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;

public class TextEventContentExtractor {
	public static Text getHoverEventContent(HoverEvent event) {
		return switch (event) {
			case HoverEvent.ShowText showText -> showText.value();
			case HoverEvent.ShowEntity showEntity -> Texts.join(showEntity.entity().asTooltip(), (t) -> t);
			case HoverEvent.ShowItem showItem -> showItem.item().toHoverableText();
			default -> Text.literal("[UNRECOGNIZED HOVER EVENT]: " + event.getAction());
		};
	}

	public static String getClickEventContent(ClickEvent event) {
		return switch (event) {
			case ClickEvent.ChangePage changePage -> Integer.toString(changePage.page());
			case ClickEvent.CopyToClipboard toClipboard -> toClipboard.value();
			case ClickEvent.OpenFile openFile -> openFile.file().toString();
			case ClickEvent.OpenUrl openUrl -> openUrl.toString();
			case ClickEvent.RunCommand runCmd -> runCmd.command();
			case ClickEvent.SuggestCommand suggestCmd -> suggestCmd.command();
			default -> "[UNRECOGNIZED HOVER EVENT]: " + event.getAction();
		};
	}
	
}

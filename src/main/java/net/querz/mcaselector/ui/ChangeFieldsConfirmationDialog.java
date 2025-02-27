package net.querz.mcaselector.ui;

import javafx.stage.Stage;
import net.querz.mcaselector.tiles.TileMap;
import net.querz.mcaselector.util.Translation;

public class ChangeFieldsConfirmationDialog extends ConfirmationDialog {

	public ChangeFieldsConfirmationDialog(TileMap tileMap, Stage primaryStage) {
		super(
				primaryStage,
				Translation.DIALOG_CHANGE_NBT_CONFIRMATION_TITLE,
				Translation.DIALOG_CHANGE_NBT_CONFIRMATION_HEADER_SHORT,
				"change"
		);

		if (tileMap != null) {
			headerTextProperty().unbind();
			setHeaderText(String.format(Translation.DIALOG_CHANGE_NBT_CONFIRMATION_HEADER_VERBOSE.toString(), tileMap.getSelectedChunks()));
		}
	}
}

package io.github.sspanak.tt9.ime;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.Logger;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.ui.UI;

public class TraditionalT9 extends KeyPadHandler {
	private static TraditionalT9 self;

	// input mode
	private ArrayList<Integer> allowedInputModes = new ArrayList<>();
	private InputMode mInputMode;

	// text case
	private ArrayList<Integer> allowedTextCases = new ArrayList<>();
	private int mTextCase = InputMode.CASE_LOWER;

	// language
	protected ArrayList<Integer> mEnabledLanguages;
	protected Language mLanguage;

	// soft key view
	private SoftKeyHandler softKeyHandler = null;



	public static Context getMainContext() {
		return self.getApplicationContext();
	}


	private void loadPreferences() {
		mLanguage = LanguageCollection.getLanguage(prefs.getInputLanguage());
		mEnabledLanguages = prefs.getEnabledLanguages();
		mInputMode = InputMode.getInstance(prefs.getInputMode());
		mTextCase = prefs.getTextCase();
	}

	private void validateLanguages() {
		mEnabledLanguages = InputModeValidator.validateEnabledLanguages(prefs, mEnabledLanguages);
		mLanguage = InputModeValidator.validateLanguage(prefs, mLanguage, mEnabledLanguages);
	}

	private void validatePreferences() {
		validateLanguages();
		mInputMode = InputModeValidator.validateMode(prefs, mInputMode, allowedInputModes);
		mTextCase = InputModeValidator.validateTextCase(prefs, mTextCase, allowedTextCases);
	}


	protected void onInit() {
		self = this;

		if (softKeyHandler == null) {
			softKeyHandler = new SoftKeyHandler(getLayoutInflater(), this);
		}

		loadPreferences();
		prefs.clearLastWord();
	}


	protected void onRestart(EditorInfo inputField) {
		// determine the valid state for the current input field and preferences
		determineAllowedInputModes(inputField);
		determineAllowedTextCases();
		mEnabledLanguages = prefs.getEnabledLanguages(); // in case we are back from Preferences screen, update the language list

		// enforce a valid initial state
		validatePreferences();
		clearSuggestions();

		// build the UI
		UI.updateStatusIcon(this, mLanguage, mInputMode, mTextCase);
		softKeyHandler.show();
		if (!isInputViewShown()) {
			showWindow(true);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mEditing != EDITING_STRICT_NUMERIC && mEditing != EDITING_DIALER) {
			requestShowSelf(1);
		}

		restoreAddedWordIfAny();
	}


	protected void onFinish() {
		clearSuggestions();

		hideStatusIcon();
		hideWindow();

		softKeyHandler.hide();
	}


	public boolean onBackspace() {
		if (!InputFieldHelper.isThereText(currentInputConnection)) {
			Logger.d("onBackspace", "backspace ignored");
			mInputMode.reset();
			return false;
		}

		resetKeyRepeat();

		if (mInputMode.onBackspace()) {
			getSuggestions();
		} else {
			commitCurrentSuggestion();
			super.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
		}

		Logger.d("onBackspace", "backspace handled");
		return true;
	}


	public boolean onOK() {
		Logger.d("onOK", "enter handler");

		acceptCurrentSuggestion();
		resetKeyRepeat();
		mInputMode.reset();

		return !isSuggestionViewHidden();
	}


	protected boolean onUp() {
		return previousSuggestion();
	}


	protected boolean onDown() {
		return nextSuggestion();
	}

	/**
	 * onNumber
	 *
	 * @param key     Must be a number from 1 to 9, not a "KeyEvent.KEYCODE_X"
	 * @param hold    If "true" we are calling the handler, because the key is being held.
	 * @param repeat  If "true" we are calling the handler, because the key was pressed more than once
	 * @return boolean
	 */
	protected boolean onNumber(int key, boolean hold, boolean repeat) {
		if (mInputMode.shouldAcceptCurrentSuggestion(key, hold, repeat)) {
			acceptCurrentSuggestion();
		}

		if (!mInputMode.onNumber(mLanguage, key, hold, repeat)) {
			return false;
		}

		if (mInputMode.shouldSelectNextSuggestion() && !isSuggestionViewHidden()) {
			nextSuggestion();
			return true;
		}

		if (mInputMode.getWord() != null) {
			setText(mInputMode.getWord());
		} else {
			getSuggestions();
		}

		return true;
	}


	protected boolean onPound() {
		setText("#");
		return true;
	}


	protected boolean onStar() {
		setText("*");
		return true;
	}


	protected boolean onKeyInputMode(boolean hold) {
		if (mEditing == EDITING_DIALER) {
			return false;
		}

		if (hold) {
			nextLang();
		} else {
			nextInputMode();
		}

		return true;
	}


	protected boolean onKeyOtherAction(boolean hold) {
		if (mEditing == EDITING_NOSHOW || mEditing == EDITING_DIALER) {
			return false;
		}

		if (hold) {
			UI.showPreferencesScreen(this);
		} else {
			showAddWord();
		}

		return true;
	}


	protected boolean shouldTrackNumPress() {
		return mInputMode.shouldTrackNumPress();
	}


	protected boolean shouldTrackArrows() {
		return mEditing != EDITING_NOSHOW && !isSuggestionViewHidden();
	}


	private boolean isSuggestionViewHidden() {
		return mSuggestionView == null || !mSuggestionView.isShown();
	}


	private boolean previousSuggestion() {
		if (isSuggestionViewHidden()) {
			return false;
		}

		mSuggestionView.scrollToSuggestion(-1);
		setComposingTextFromCurrentSuggestion();

		return true;
	}


	private boolean nextSuggestion() {
		if (isSuggestionViewHidden()) {
			return false;
		}

		mSuggestionView.scrollToSuggestion(1);
		setComposingTextFromCurrentSuggestion();

		return true;
	}

	private void handleSuggestions(ArrayList<String> suggestions, int maxWordLength) {
		setSuggestions(suggestions);

		// Put the first suggestion in the text field,
		// but cut it off to the length of the sequence (how many keys were pressed),
		// for a more intuitive experience.
		String word = mSuggestionView.getCurrentSuggestion();
		word = word.substring(0, Math.min(maxWordLength, word.length()));
		setComposingText(word);
	}

	private final Handler handleSuggestionsAsync = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(Message msg) {
			handleSuggestions(
				msg.getData().getStringArrayList("suggestions"),
				msg.getData().getInt("maxWordLength", 1000)
			);
		}
	};


	private void acceptCurrentSuggestion() {
		mInputMode.onAcceptSuggestion(mLanguage, mSuggestionView.getCurrentSuggestion());
		commitCurrentSuggestion();
	}


	private void commitCurrentSuggestion() {
		// commit the current suggestion to the input field
		if (!isSuggestionViewHidden()) {
			if (mSuggestionView.getCurrentSuggestion().equals(" ")) {
				// finishComposingText() seems to ignore a single space,
				// so we have to force commit it.
				setText(" ");
			} else {
				currentInputConnection.finishComposingText();
			}
		}

		setSuggestions(null);
	}


	private void clearSuggestions() {
		setSuggestions(null);

		if (currentInputConnection != null) {
			setComposingTextFromCurrentSuggestion();
			currentInputConnection.finishComposingText();
		}
	}

	private void getSuggestions() {
		if (!mInputMode.getSuggestionsAsync(handleSuggestionsAsync, mLanguage, mSuggestionView.getCurrentSuggestion())) {
			handleSuggestions(mInputMode.getSuggestions(), 1);
		}
	}

	private void setSuggestions(List<String> suggestions) {
		if (mSuggestionView == null) {
			return;
		}

		boolean show = suggestions != null && suggestions.size() > 0;

		mSuggestionView.setSuggestions(suggestions, 0);
		mSuggestionView.changeCase(mTextCase, mLanguage.getLocale());
		setCandidatesViewShown(show);
	}

	private void setText(String text) {
		if (text != null) {
			currentInputConnection.commitText(text, text.length());
		}
	}

	private void setComposingText(String text) {
		currentInputConnection.setComposingText(text, 1);
	}


	private void nextInputMode() {
		if (mEditing == EDITING_STRICT_NUMERIC || mEditing == EDITING_DIALER) {
			clearSuggestions();
			mInputMode = InputMode.getInstance(InputMode.MODE_123);
		}
		// when typing a word or viewing scrolling the suggestions, only change the case
		else if (!isSuggestionViewHidden()) {
			determineAllowedTextCases();

			int modeIndex = (allowedTextCases.indexOf(mTextCase) + 1) % allowedTextCases.size();
			mTextCase = allowedTextCases.get(modeIndex);

			mSuggestionView.changeCase(mTextCase, mLanguage.getLocale());
			setComposingTextFromCurrentSuggestion();
		}
		// make "abc" and "ABC" separate modes from user perspective
		else if (mInputMode.isABC() && mTextCase == InputMode.CASE_LOWER) {
			mTextCase = InputMode.CASE_UPPER;
		} else {
			int modeIndex = (allowedInputModes.indexOf(mInputMode.getId()) + 1) % allowedInputModes.size();
			mInputMode = InputMode.getInstance(allowedInputModes.get(modeIndex));

			mTextCase = mInputMode.isPredictive() ? InputMode.CASE_CAPITALIZE : InputMode.CASE_LOWER;
		}

		// save the settings for the next time
		prefs.saveInputMode(mInputMode);
		prefs.saveTextCase(mTextCase);

		UI.updateStatusIcon(this, mLanguage, mInputMode, mTextCase);
	}

	private void setComposingTextFromCurrentSuggestion() {
		if (!isSuggestionViewHidden()) {
			setComposingText(mSuggestionView.getCurrentSuggestion());
		}
	}


	private void nextLang() {
		if (mEditing == EDITING_STRICT_NUMERIC || mEditing == EDITING_DIALER) {
			return;
		}

		clearSuggestions();

		// select the next language
		int previousLangId = mEnabledLanguages.indexOf(mLanguage.getId());
		int nextLangId = previousLangId == -1 ? 0 : (previousLangId + 1) % mEnabledLanguages.size();
		mLanguage = LanguageCollection.getLanguage(mEnabledLanguages.get(nextLangId));

		validateLanguages();

		// save it for the next time
		prefs.saveInputLanguage(mLanguage.getId());

		UI.updateStatusIcon(this, mLanguage, mInputMode, mTextCase);
	}


	private void determineAllowedInputModes(EditorInfo inputField) {
		allowedInputModes = InputFieldHelper.determineInputModes(inputField);

		int lastInputModeId = prefs.getInputMode();
		if (allowedInputModes.contains(lastInputModeId)) {
			mInputMode = InputMode.getInstance(lastInputModeId);
		} else if (allowedInputModes.contains(InputMode.MODE_ABC)) {
			mInputMode = InputMode.getInstance(InputMode.MODE_ABC);
		} else {
			mInputMode = InputMode.getInstance(allowedInputModes.get(0));
		}

		if (InputFieldHelper.isDialerField(inputField)) {
			mEditing = EDITING_DIALER;
		} else if (mInputMode.is123() && allowedInputModes.size() == 1) {
			mEditing = EDITING_STRICT_NUMERIC;
		} else {
			mEditing = InputFieldHelper.isFilterTextField(inputField) ? EDITING_NOSHOW : EDITING;
		}
	}


	private void determineAllowedTextCases() {
		allowedTextCases = mInputMode.getAllowedTextCases();
		// @todo: determine the text case of the input and validate using the allowed ones
	}


	private void showAddWord() {
		acceptCurrentSuggestion();
		clearSuggestions();

		UI.showAddWordDialog(this, mLanguage.getId(), InputFieldHelper.getSurroundingWord(currentInputConnection));
	}


	/**
	 * restoreAddedWordIfAny
	 * If a new word was added to the dictionary, this function will append add it to the current input field.
	 */
	private void restoreAddedWordIfAny() {
		String word = prefs.getLastWord();
		prefs.clearLastWord();

		if (word.length() == 0 || word.equals(InputFieldHelper.getSurroundingWord(currentInputConnection))) {
			return;
		}

		try {
			Logger.d("restoreAddedWordIfAny", "Restoring word: '" + word + "'...");
			setText(word);
			mInputMode.reset();
		} catch (Exception e) {
			Logger.w("tt9/restoreLastWord", "Could not restore the last added word. " + e.getMessage());
		}
	}


	/**
	 * createSoftKeyView
	 * Generates the actual UI of TT9.
	 */
	protected View createSoftKeyView() {
		return softKeyHandler.createView(getLayoutInflater());
	}
}

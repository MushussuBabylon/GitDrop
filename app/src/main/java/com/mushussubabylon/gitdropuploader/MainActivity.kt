package com.mushussubabylon.gitdropuploader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * GitDrop's screen flow: access token -> repository and branch -> pick files -> review -> upload.
 *
 * All network and file work happens on background threads; the actual uploading is done by
 * [Uploader], which keeps to GitHub's rate limits (see its documentation).
 */
class MainActivity : Activity() {

    private lateinit var flipper: ViewFlipper

    // screen 1
    private lateinit var createTokenBtn: Button
    private lateinit var tokenInput: EditText
    private lateinit var showToken: CheckBox
    private lateinit var validateBtn: Button
    private lateinit var tokenProgress: ProgressBar
    private lateinit var tokenStatus: TextView

    // screen 2
    private lateinit var signedInText: TextView
    private lateinit var repoFilter: EditText
    private lateinit var repoSpinner: Spinner
    private lateinit var branchSpinner: Spinner
    private lateinit var continueBtn: Button
    private lateinit var backToTokenBtn: ImageButton
    private lateinit var newBranchBtn: Button
    private lateinit var repoProgress: ProgressBar
    private lateinit var repoStatus: TextView

    // screen 3
    private lateinit var destSummary: TextView
    private lateinit var pickFilesBtn: Button
    private lateinit var pickFolderBtn: Button
    private lateinit var selectionLabel: TextView
    private lateinit var selectionList: TextView
    private lateinit var clearSelectionBtn: Button
    private lateinit var repoPrefix: EditText
    private lateinit var browseRepoFolderBtn: Button
    private lateinit var commitMessage: EditText
    private lateinit var pushBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var backToRepoBtn: ImageButton
    private lateinit var step3Expl: TextView
    private lateinit var screen3Scroll: ScrollView
    private lateinit var uploadProgress: ProgressBar
    private lateinit var uploadStatus: TextView
    private lateinit var uploadDetail: TextView
    private lateinit var uploadResult: TextView

    // screen 4: review before upload
    private lateinit var reviewBackBtn: ImageButton
    private lateinit var reviewList: ListView
    private lateinit var reviewUploadBtn: Button
    private lateinit var reviewSummary: TextView
    private lateinit var reviewCounts: TextView
    private lateinit var reviewNotes: TextView
    private lateinit var reviewSecretBanner: TextView
    private lateinit var reviewIdentityBanner: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterNew: TextView
    private lateinit var filterChanged: TextView
    private lateinit var filterFlagged: TextView
    private lateinit var reviewSelectAll: Button
    private lateinit var reviewSelectNone: Button
    private lateinit var reviewEmpty: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val scanner by lazy { DocumentScanner(contentResolver) }

    // --- session (memory only: nothing about the token is ever written to disk) ---
    private var token = ""
    private var api: GitHubApi? = null
    private var allRepos: List<GitHubApi.RepoInfo> = emptyList()
    private var shownRepos: List<GitHubApi.RepoInfo> = emptyList()

    /** Branches of [branchesRepo], default branch first. Empty for an empty repository. */
    private var branches: List<String> = emptyList()
    private var branchesRepo: String? = null
    private var repoIsEmpty = false
    private var branchReqId = 0

    /** A branch that was just created: selected as soon as the branch list has been reloaded. */
    private var branchToSelect: String? = null

    private val selected = ArrayList<Sel>()

    /** Files shared to GitDrop from other apps, waiting for the person to reach the selection screen. */
    private val pendingShared = ArrayList<Uri>()

    // --- upload state ---
    @Volatile private var uploading = false
    @Volatile private var cancelRequested = false
    @Volatile private var uploader: Uploader? = null

    // --- review state (screen 4) ---
    @Volatile private var reviewing = false
    @Volatile private var planner: ChangePlanner? = null
    @Volatile private var rvStep = ReviewStep.CHECKING_BRANCH
    @Volatile private var rvCur = 0
    @Volatile private var rvTotal = 0
    private var review: ReviewSession? = null
    private val reviewRows = ArrayList<ReviewRow>()
    private var shownRows: List<ReviewRow> = emptyList()
    private var reviewFilter = RowFilter.ALL
    private var reviewAdapter: ReviewAdapter? = null

    // Written by worker threads, read on the UI thread.
    @Volatile private var pScanning = false
    @Volatile private var pScanFound = 0
    @Volatile private var pPhase: Phase? = null
    @Volatile private var pCur = 0
    @Volatile private var pTotal = 0
    @Volatile private var pFilesDone = 0
    @Volatile private var pFilesTotal = 0
    @Volatile private var pBytesDone = 0L
    @Volatile private var pBytesTotal = 0L
    @Volatile private var pSpeed = 0L
    @Volatile private var pWaitStamp = 0L
    @Volatile private var pWaitSeconds = 0L
    @Volatile private var pWaitPrimary = false
    @Volatile private var pRetryStamp = 0L
    private val renderPending = AtomicBoolean(false)
    private val refreshNotice = Runnable { renderProgress() }

    /** One line of the review list: a file and whether it is ticked. */
    private class ReviewRow(val entry: ReviewEntry, var checked: Boolean)

    /** What the review was made for; the upload that follows uses exactly this. */
    private class ReviewSession(
        val repo: String,
        val branch: String,
        val message: String,
        val scan: ScanResult,
        val result: ReviewResult
    )

    /** What the review already decided about files that will NOT be uploaded (for the final report). */
    private class Extras(
        val skipped: List<Skipped>,
        val leftOut: List<String>,
        val leftOutSecrets: List<String>
    )

    private enum class RowFilter { ALL, NEW, CHANGED, FLAGGED }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireEvents()
        refreshSelection()
        cleanStaleTempFiles()
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    /** GitDrop is a "share to" target: a share that arrives while the app is open lands here. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            cancelRequested = true
            uploader?.cancel()
            planner?.cancel()
            api?.cancel()
        }
        api?.close()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            uploading -> confirmStop()
            reviewing -> requestStop()
            flipper.displayedChild == 3 -> leaveReview()
            flipper.displayedChild == 2 -> flipper.displayedChild = 1
            flipper.displayedChild == 1 -> flipper.displayedChild = 0
            else -> super.onBackPressed()
        }
    }

    private fun bindViews() {
        flipper = findViewById(R.id.flipper)
        createTokenBtn = findViewById(R.id.createTokenBtn)
        tokenInput = findViewById(R.id.tokenInput)
        showToken = findViewById(R.id.showToken)
        validateBtn = findViewById(R.id.validateBtn)
        tokenProgress = findViewById(R.id.tokenProgress)
        tokenStatus = findViewById(R.id.tokenStatus)
        signedInText = findViewById(R.id.signedInText)
        repoFilter = findViewById(R.id.repoFilter)
        repoSpinner = findViewById(R.id.repoSpinner)
        branchSpinner = findViewById(R.id.branchSpinner)
        continueBtn = findViewById(R.id.continueBtn)
        backToTokenBtn = findViewById(R.id.backToTokenBtn)
        newBranchBtn = findViewById(R.id.newBranchBtn)
        repoProgress = findViewById(R.id.repoProgress)
        repoStatus = findViewById(R.id.repoStatus)
        destSummary = findViewById(R.id.destSummary)
        pickFilesBtn = findViewById(R.id.pickFilesBtn)
        pickFolderBtn = findViewById(R.id.pickFolderBtn)
        selectionLabel = findViewById(R.id.selectionLabel)
        selectionList = findViewById(R.id.selectionList)
        clearSelectionBtn = findViewById(R.id.clearSelectionBtn)
        repoPrefix = findViewById(R.id.repoPrefix)
        browseRepoFolderBtn = findViewById(R.id.browseRepoFolderBtn)
        commitMessage = findViewById(R.id.commitMessage)
        pushBtn = findViewById(R.id.pushBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        backToRepoBtn = findViewById(R.id.backToRepoBtn)
        uploadProgress = findViewById(R.id.uploadProgress)
        uploadStatus = findViewById(R.id.uploadStatus)
        uploadDetail = findViewById(R.id.uploadDetail)
        uploadResult = findViewById(R.id.uploadResult)
        step3Expl = findViewById(R.id.step3Expl)
        screen3Scroll = findViewById(R.id.screen3Scroll)

        reviewBackBtn = findViewById(R.id.reviewBackBtn)
        reviewList = findViewById(R.id.reviewList)
        reviewUploadBtn = findViewById(R.id.reviewUploadBtn)
        // Summary, warnings and filters are the header of the list, so they scroll away with the files.
        val header = layoutInflater.inflate(R.layout.review_header, reviewList, false)
        reviewSummary = header.findViewById(R.id.reviewSummary)
        reviewCounts = header.findViewById(R.id.reviewCounts)
        reviewNotes = header.findViewById(R.id.reviewNotes)
        reviewSecretBanner = header.findViewById(R.id.reviewSecretBanner)
        reviewIdentityBanner = header.findViewById(R.id.reviewIdentityBanner)
        filterAll = header.findViewById(R.id.filterAll)
        filterNew = header.findViewById(R.id.filterNew)
        filterChanged = header.findViewById(R.id.filterChanged)
        filterFlagged = header.findViewById(R.id.filterFlagged)
        reviewSelectAll = header.findViewById(R.id.reviewSelectAll)
        reviewSelectNone = header.findViewById(R.id.reviewSelectNone)
        reviewEmpty = header.findViewById(R.id.reviewEmpty)
        reviewList.addHeaderView(header, null, false)
        reviewAdapter = ReviewAdapter()
        reviewList.adapter = reviewAdapter
    }

    private fun wireEvents() {
        createTokenBtn.setOnClickListener { openUrl(TOKEN_PAGE) }
        showToken.setOnCheckedChangeListener { _, checked ->
            tokenInput.inputType = if (checked)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            tokenInput.setSelection(tokenInput.text.length)
        }
        validateBtn.setOnClickListener { validateToken() }
        tokenInput.setOnEditorActionListener { _, _, _ ->
            validateToken()
            true
        }

        backToTokenBtn.setOnClickListener { flipper.displayedChild = 0 }
        continueBtn.setOnClickListener { onContinue() }
        newBranchBtn.setOnClickListener { promptNewBranch() }
        repoFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyRepoFilter()
            }
        })
        repoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onRepoChosen()
            }
        }

        backToRepoBtn.setOnClickListener { flipper.displayedChild = 1 }
        pickFilesBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startPicker(i, REQ_FILES)
        }
        pickFolderBtn.setOnClickListener { startPicker(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER) }
        clearSelectionBtn.setOnClickListener {
            selected.clear()
            refreshSelection()
        }
        browseRepoFolderBtn.setOnClickListener { openRepoFolderBrowser() }
        pushBtn.setOnClickListener { startReview() }
        step3Expl.setOnClickListener {
            // The long explanation starts folded (3 lines); a tap unfolds or folds it.
            step3Expl.maxLines = if (step3Expl.maxLines > 3) 3 else Int.MAX_VALUE
        }
        wireReviewScreen()
        cancelBtn.setOnClickListener { requestStop() }
        repoPrefix.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshSelection()
            }
        })
    }

    private fun startPicker(intent: Intent, code: Int) {
        try {
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_picker)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_browser)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                } else {
                    data.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) addPickedFiles(uris)
            }
            REQ_FOLDER -> data.data?.let { openFolderBrowser(it) }
        }
    }

    // ---------------------------------------------------------------- screen 1: token

    private fun validateToken() {
        if (tokenProgress.visibility == View.VISIBLE) return // already working
        val t = tokenInput.text.toString().trim()
        if (t.isEmpty()) {
            showStatus(tokenStatus, getString(R.string.err_paste_key), true)
            return
        }
        if (!t.all { it.code in 33..126 }) {
            showStatus(tokenStatus, getString(R.string.err_token_chars), true)
            return
        }
        showStatus(tokenStatus, "", false)
        tokenProgress.visibility = View.VISIBLE
        validateBtn.isEnabled = false
        thread(name = "gitdrop-login") {
            val a = GitHubApi(t)
            val login: String
            val repos: List<GitHubApi.RepoInfo>
            try {
                login = a.user()
            } catch (e: Throwable) {
                a.close()
                runOnUiThread { tokenFailed(failureText(e, R.string.key_rejected)) }
                return@thread
            }
            try {
                repos = a.repos()
            } catch (e: Throwable) {
                a.close()
                runOnUiThread { tokenFailed(failureText(e, R.string.err_load_repos)) }
                return@thread
            }
            runOnUiThread { signedIn(t, a, login, repos) }
        }
    }

    private fun tokenFailed(message: String) {
        tokenProgress.visibility = View.GONE
        validateBtn.isEnabled = true
        showStatus(tokenStatus, message, true)
    }

    private fun signedIn(t: String, a: GitHubApi, login: String, repos: List<GitHubApi.RepoInfo>) {
        api?.close()
        api = a
        token = t
        tokenProgress.visibility = View.GONE
        validateBtn.isEnabled = true
        showStatus(tokenStatus, "", false)

        signedInText.text = if (login.isEmpty()) "" else getString(R.string.signed_in_as, login)
        allRepos = repos
        branches = emptyList()
        branchesRepo = null
        repoIsEmpty = false
        branchReqId++
        repoFilter.setText("")
        repoFilter.visibility = if (repos.size > FILTER_FROM) View.VISIBLE else View.GONE
        showBranchControls(false)
        repoProgress.visibility = View.GONE
        continueBtn.isEnabled = false
        selected.clear()
        refreshSelection()
        flipper.displayedChild = 1
        if (repos.isEmpty()) {
            shownRepos = emptyList()
            repoSpinner.adapter = null
            showStatus(repoStatus, getString(R.string.no_push_repos), true)
        } else {
            showStatus(repoStatus, "", false)
            applyRepoFilter()
        }
    }

    // ---------------------------------------------------------------- screen 2: repository + branch

    private fun applyRepoFilter() {
        if (allRepos.isEmpty()) return
        val q = repoFilter.text.toString().trim().lowercase()
        val keep = currentRepo()
        shownRepos = if (q.isEmpty()) allRepos else allRepos.filter { it.fullName.lowercase().contains(q) }
        val names = shownRepos.map { it.fullName }
        repoSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val at = if (keep != null) names.indexOf(keep) else -1
        if (at >= 0) repoSpinner.setSelection(at)
        if (names.isEmpty()) {
            branchReqId++
            branchesRepo = null
            branches = emptyList()
            showBranchControls(false)
            repoProgress.visibility = View.GONE
            continueBtn.isEnabled = false
            showStatus(repoStatus, getString(R.string.no_match), false)
        } else if (repoStatus.text == getString(R.string.no_match)) {
            showStatus(repoStatus, "", false)
        }
    }

    private fun currentRepo(): String? =
        shownRepos.getOrNull(repoSpinner.selectedItemPosition)?.fullName

    private fun onRepoChosen() {
        val repo = currentRepo() ?: return
        if (repo == branchesRepo) return // already loaded (or loading)
        loadBranches(repo)
    }

    private fun loadBranches(repo: String) {
        val a = api ?: return
        val req = ++branchReqId
        branchesRepo = repo
        branches = emptyList()
        repoIsEmpty = false
        showBranchControls(false)
        continueBtn.isEnabled = false
        repoProgress.visibility = View.VISIBLE
        showStatus(repoStatus, getString(R.string.loading_branches, repo), false)
        thread(name = "gitdrop-branches") {
            try {
                val list = a.branches(repo)
                runOnUiThread { if (req == branchReqId) branchesLoaded(repo, list) }
            } catch (e: Throwable) {
                runOnUiThread { if (req == branchReqId) branchesFailed(e) }
            }
        }
    }

    private fun branchesLoaded(repo: String, list: List<String>) {
        repoProgress.visibility = View.GONE
        continueBtn.isEnabled = true
        val def = shownRepos.firstOrNull { it.fullName == repo }?.defaultBranch ?: ""
        if (list.isEmpty()) {
            // No branch at all: the repository has no commit yet. The upload creates the first one.
            repoIsEmpty = true
            branches = emptyList()
            showBranchControls(false)
            showStatus(repoStatus, getString(R.string.empty_repo_msg), false)
            return
        }
        val ordered = ArrayList<String>(list.size + 1)
        if (def.isNotEmpty() && list.contains(def)) ordered.add(def)
        for (b in list) if (b != def) ordered.add(b)
        val wanted = branchToSelect
        branchToSelect = null
        if (wanted != null && !ordered.contains(wanted)) ordered.add(wanted)
        branches = ordered
        val labels = ordered.map { if (it == def) getString(R.string.default_branch_label, it) else it }
        branchSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        branchSpinner.setSelection(if (wanted != null) maxOf(0, ordered.indexOf(wanted)) else 0)
        showBranchControls(true)
        showStatus(repoStatus, "", false)
    }

    private fun branchesFailed(e: Throwable) {
        branchesRepo = null // selecting the repository again, or tapping Continue, tries again
        repoProgress.visibility = View.GONE
        continueBtn.isEnabled = true
        showStatus(repoStatus, failureText(e, R.string.err_load_branches), true)
    }

    private fun currentBranch(): String {
        if (repoIsEmpty) {
            val repo = currentRepo()
            val def = shownRepos.firstOrNull { it.fullName == repo }?.defaultBranch ?: ""
            return def.ifEmpty { "main" }
        }
        return branches.getOrNull(branchSpinner.selectedItemPosition) ?: ""
    }

    private fun onContinue() {
        val repo = currentRepo()
        if (repo == null) {
            showStatus(repoStatus, getString(R.string.pick_repo_first), true)
            return
        }
        if (branchesRepo != repo) { // the list never arrived: try again
            loadBranches(repo)
            return
        }
        if (!repoIsEmpty && branches.isEmpty()) {
            showStatus(repoStatus, getString(R.string.pick_branch_wait), true)
            return
        }
        showStatus(repoStatus, "", false)
        destSummary.text = getString(R.string.dest_summary, repo, currentBranch())
        uploadStatus.text = ""
        uploadDetail.text = ""
        uploadResult.text = ""
        refreshSelection()
        flipper.displayedChild = 2
        consumePendingShared()
    }

    // ---------------------------------------------------------------- screen 3: selection

    private fun prefixText(): String {
        val parts = repoPrefix.text.toString().replace('\\', '/').split('/')
            .map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        return if (parts.isEmpty()) "" else parts.joinToString("/") + "/"
    }

    private fun addPickedFiles(uris: List<Uri>) {
        selectionLabel.text = getString(R.string.reading_selection)
        thread(name = "gitdrop-describe") {
            val picked = uris.map { u ->
                val (name, size) = scanner.describe(u)
                Sel.SingleFile(u, name, size)
            }
            runOnUiThread {
                for (s in picked) {
                    if (selected.none { it is Sel.SingleFile && it.uri == s.uri }) selected.add(s)
                }
                refreshSelection()
            }
        }
    }

    private fun refreshSelection() {
        if (selected.isEmpty()) {
            selectionLabel.text = getString(R.string.selected_none)
            selectionList.text = ""
            return
        }
        val files = selected.count { it is Sel.SingleFile }
        val folders = selected.count { it is Sel.Tree }
        selectionLabel.text = getString(R.string.selected_summary, files, folders)
        val p = prefixText()
        val lines = ArrayList<String>()
        for (s in selected.take(MAX_LISTED)) {
            lines.add(
                when (s) {
                    is Sel.SingleFile -> getString(R.string.sel_file, p + s.name)
                    is Sel.Tree ->
                        if (s.excluded.isEmpty()) getString(R.string.sel_folder, s.rootName)
                        else getString(R.string.sel_folder_partial, s.rootName, s.excluded.size)
                }
            )
        }
        if (selected.size > MAX_LISTED) lines.add(getString(R.string.sel_more, selected.size - MAX_LISTED))
        selectionList.text = lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- folder browser (this phone)

    private fun openFolderBrowser(treeUri: Uri) {
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            try {
                DocumentsContract.getDocumentId(treeUri)
            } catch (e2: Exception) {
                toast(R.string.cannot_open_folder)
                return
            }
        }
        val before = selected.firstOrNull { it is Sel.Tree && it.treeUri == treeUri } as Sel.Tree?
        val excluded = HashSet<String>(before?.excluded ?: emptySet())
        var rootName = before?.rootName ?: ""
        var rootKids: List<DocumentScanner.Child>? = null
        val stack = ArrayList<Pair<String, String>>() // document id -> path below the picked folder
        stack.add(Pair(rootDocId, ""))
        var req = 0

        val view = layoutInflater.inflate(R.layout.dialog_browser, null)
        val pathText = view.findViewById<TextView>(R.id.browserPath)
        val upBtn = view.findViewById<Button>(R.id.browserUp)
        val allBtn = view.findViewById<Button>(R.id.browserAll)
        val noneBtn = view.findViewById<Button>(R.id.browserNone)
        val progress = view.findViewById<ProgressBar>(R.id.browserProgress)
        val message = view.findViewById<TextView>(R.id.browserMessage)
        val list = view.findViewById<ListView>(R.id.browserList)
        list.layoutParams.height = resources.displayMetrics.heightPixels * 55 / 100

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.browse_title))
            .setView(view)
            .setPositiveButton(getString(R.string.done_btn), null)
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .create()

        var shown: List<DocumentScanner.Child> = emptyList()
        var adapter: ArrayAdapter<DocumentScanner.Child>? = null
        var reload: () -> Unit = {} // set to render() below (a local function cannot be used before its declaration)

        fun showKids(kids: List<DocumentScanner.Child>, rel: String) {
            shown = kids
            message.visibility = if (kids.isEmpty()) View.VISIBLE else View.GONE
            message.text = getString(R.string.no_items)
            val a = object : ArrayAdapter<DocumentScanner.Child>(this, R.layout.item_browser, R.id.itemName, kids) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val e = kids[position]
                    val cb = v.findViewById<CheckBox>(R.id.itemCheck)
                    val name = v.findViewById<TextView>(R.id.itemName)
                    // The view may be recycled: detach the old listener before restoring the state.
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = e.docId !in excluded
                    cb.setOnCheckedChangeListener { _, checked ->
                        if (checked) excluded.remove(e.docId) else excluded.add(e.docId)
                    }
                    name.text = if (e.isDir) "📁 ${e.name}" else "📄 ${e.name}"
                    name.setOnClickListener {
                        if (e.isDir) {
                            stack.add(Pair(e.docId, if (rel.isEmpty()) e.name else "$rel/${e.name}"))
                            reload()
                        } else {
                            cb.toggle() // tapping a file's name ticks or unticks it
                        }
                    }
                    return v
                }
            }
            adapter = a
            list.adapter = a
        }

        fun render() {
            val (docId, rel) = stack.last()
            pathText.text = when {
                rootName.isEmpty() -> if (rel.isEmpty()) getString(R.string.root_folder) else "/$rel"
                rel.isEmpty() -> "$rootName/"
                else -> "$rootName/$rel"
            }
            upBtn.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
            val my = ++req
            progress.visibility = View.VISIBLE
            message.visibility = View.GONE
            list.adapter = null
            thread(name = "gitdrop-list") {
                var kids: List<DocumentScanner.Child>? = null
                try {
                    if (rootName.isEmpty()) rootName = scanner.treeRootName(treeUri, rootDocId)
                    kids = scanner.listChildren(treeUri, docId)
                } catch (_: Exception) {
                }
                runOnUiThread {
                    if (my != req || !dialog.isShowing) return@runOnUiThread
                    progress.visibility = View.GONE
                    if (kids == null) {
                        shown = emptyList()
                        message.visibility = View.VISIBLE
                        message.text = getString(R.string.cannot_open_folder)
                    } else {
                        if (stack.size == 1) rootKids = kids
                        showKids(kids, rel)
                    }
                    pathText.text = when {
                        rel.isEmpty() -> "$rootName/"
                        else -> "$rootName/$rel"
                    }
                }
            }
        }

        reload = ::render
        upBtn.setOnClickListener {
            if (stack.size > 1) {
                stack.removeAt(stack.size - 1)
                render()
            }
        }
        allBtn.setOnClickListener {
            for (k in shown) excluded.remove(k.docId)
            adapter?.notifyDataSetChanged()
        }
        noneBtn.setOnClickListener {
            for (k in shown) excluded.add(k.docId)
            adapter?.notifyDataSetChanged()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                selected.removeAll { it is Sel.Tree && it.treeUri == treeUri }
                val top = rootKids
                val nothingLeft = top != null && top.all { it.docId in excluded }
                if (!nothingLeft) {
                    selected.add(Sel.Tree(treeUri, rootDocId, rootName.ifEmpty { "folder" }, excluded.toSet()))
                }
                dialog.dismiss()
                refreshSelection()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        render()
    }

    // ---------------------------------------------------------------- folder browser (repository)

    private fun openRepoFolderBrowser() {
        val repo = currentRepo()
        val a = api
        if (repo == null || a == null) {
            toast(R.string.pick_repo_toast)
            return
        }
        val branch = currentBranch()
        if (branch.isEmpty()) {
            toast(R.string.pick_branch_toast)
            return
        }

        val stack = ArrayList<String>() // path segments below the repository root
        var req = 0

        val view = layoutInflater.inflate(R.layout.dialog_browser, null)
        val pathText = view.findViewById<TextView>(R.id.browserPath)
        val upBtn = view.findViewById<Button>(R.id.browserUp)
        val progress = view.findViewById<ProgressBar>(R.id.browserProgress)
        val message = view.findViewById<TextView>(R.id.browserMessage)
        val list = view.findViewById<ListView>(R.id.browserList)
        view.findViewById<View>(R.id.browserSelectRow).visibility = View.GONE
        list.layoutParams.height = resources.displayMetrics.heightPixels * 55 / 100

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.repo_folder_title, repo))
            .setView(view)
            .setPositiveButton(getString(R.string.use_this_folder), null)
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .create()

        var names: List<String> = emptyList()

        fun curPath() = stack.joinToString("/")

        fun load() {
            val my = ++req
            pathText.text = if (stack.isEmpty()) getString(R.string.repo_root) else "/${curPath()}"
            upBtn.visibility = if (stack.isEmpty()) View.GONE else View.VISIBLE
            message.visibility = View.GONE
            list.adapter = null
            if (repoIsEmpty) { // nothing to browse yet
                message.visibility = View.VISIBLE
                message.text = getString(R.string.no_folders)
                return
            }
            progress.visibility = View.VISIBLE
            val path = curPath()
            thread(name = "gitdrop-repo-folders") {
                var dirs: List<String>? = null
                var error: Throwable? = null
                try {
                    dirs = a.folders(repo, branch, path)
                } catch (e: Throwable) {
                    error = e
                }
                runOnUiThread {
                    if (my != req || !dialog.isShowing) return@runOnUiThread
                    progress.visibility = View.GONE
                    if (dirs == null) {
                        names = emptyList()
                        message.visibility = View.VISIBLE
                        message.text = failureText(error ?: IllegalStateException(), R.string.err_open_folder)
                    } else {
                        names = dirs
                        message.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
                        message.text = getString(R.string.no_folders)
                        list.adapter = ArrayAdapter(
                            this, android.R.layout.simple_list_item_1, dirs.map { "📁 $it" }
                        )
                    }
                }
            }
        }

        upBtn.setOnClickListener {
            if (stack.isNotEmpty()) {
                stack.removeAt(stack.size - 1)
                load()
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val name = names.getOrNull(position) ?: return@setOnItemClickListener
            stack.add(name)
            load()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = curPath()
                repoPrefix.setText(if (p.isEmpty()) "" else "$p/")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
        load()
    }

    // ---------------------------------------------------------------- upload

    private fun setUploadUI(busy: Boolean) {
        pushBtn.isEnabled = !busy
        pickFilesBtn.isEnabled = !busy
        pickFolderBtn.isEnabled = !busy
        clearSelectionBtn.isEnabled = !busy
        browseRepoFolderBtn.isEnabled = !busy
        backToRepoBtn.isEnabled = !busy
        repoPrefix.isEnabled = !busy
        commitMessage.isEnabled = !busy
        cancelBtn.isEnabled = busy
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        uploadProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (busy) scrollStatusIntoView()
    }

    /** Progress and result sit below the form: bring them into view without moving the keyboard focus. */
    private fun scrollStatusIntoView() {
        screen3Scroll.post {
            val content = screen3Scroll.getChildAt(0) ?: return@post
            screen3Scroll.smoothScrollTo(0, content.bottom)
        }
    }

    /**
     * Step 1 of an upload: look at what would happen (new, changed, identical, suspicious) without
     * changing anything on GitHub. The review screen then lets the person decide.
     */
    private fun startReview() {
        if (uploading || reviewing) return
        if (selected.isEmpty()) {
            toast(R.string.pick_files_first)
            return
        }
        val repo = currentRepo()
        if (repo == null) {
            uploadStatus.text = getString(R.string.no_repo_selected)
            return
        }
        val branch = currentBranch()
        if (branch.isEmpty()) {
            uploadStatus.text = getString(R.string.branch_missing)
            return
        }
        val prefix = prefixText()
        if (prefix.split('/').any { it == ".." || it.equals(".git", ignoreCase = true) }) {
            toast(R.string.err_prefix)
            return
        }
        val message = commitMessage.text.toString().trim().ifEmpty { getString(R.string.default_commit) }
        // Snapshot everything the worker needs while still on the UI thread.
        val authToken = token
        val selection = selected.toList()

        pScanning = true
        pScanFound = 0
        rvStep = ReviewStep.CHECKING_BRANCH
        rvCur = 0
        rvTotal = 0
        cancelRequested = false
        reviewing = true
        uploadResult.text = ""
        uploadDetail.text = ""
        uploadProgress.isIndeterminate = true
        setUploadUI(true)
        renderReviewProgress()

        thread(name = "gitdrop-review") {
            runReview(repo, branch, message, prefix, selection, authToken)
        }
    }

    /** Step 2 of an upload: send what the review let through. */
    private fun beginUpload(session: ReviewSession, chosen: List<ReviewRow>) {
        if (uploading || reviewing) return
        val files = chosen.map { it.entry.file }

        // Remember what is NOT going to be uploaded, for the final report.
        val skipped = ArrayList<Skipped>(session.result.skipped)
        val leftOut = ArrayList<String>()
        val leftOutSecrets = ArrayList<String>()
        for (row in reviewRows) {
            val e = row.entry
            if (e.status == FileStatus.UNCHANGED) {
                skipped.add(Skipped(e.path, SkipReason.UNCHANGED))
            } else if (!row.checked) {
                if (e.flagged) leftOutSecrets.add(e.path) else leftOut.add(e.path)
            }
        }
        val extras = Extras(skipped, leftOut, leftOutSecrets)

        pScanning = false
        pScanFound = 0
        pPhase = null
        pCur = 0; pTotal = 0
        pFilesDone = 0; pFilesTotal = 0
        pBytesDone = 0; pBytesTotal = 0; pSpeed = 0
        pWaitStamp = 0; pRetryStamp = 0
        cancelRequested = false
        uploading = true
        uploadStatus.text = ""
        uploadResult.text = ""
        uploadDetail.text = ""
        uploadProgress.isIndeterminate = true
        flipper.displayedChild = 2
        clearReviewState() // after the flip: the list is hidden now, so nothing can draw it half-empty
        setUploadUI(true)
        renderProgress()

        val authToken = token
        thread(name = "gitdrop-upload") {
            runUpload(session, files, extras, authToken)
        }
    }

    private val listener = object : UploadListener {
        override fun onPhase(phase: Phase, current: Int, total: Int) {
            pScanning = false
            pPhase = phase; pCur = current; pTotal = total
            requestRender()
        }

        override fun onProgress(filesDone: Int, filesTotal: Int, bytesDone: Long, bytesTotal: Long, bytesPerSec: Long) {
            pFilesDone = filesDone; pFilesTotal = filesTotal
            pBytesDone = bytesDone; pBytesTotal = bytesTotal
            pSpeed = bytesPerSec
            requestRender()
        }

        override fun onWait(secondsLeft: Long, primaryLimit: Boolean) {
            pWaitSeconds = secondsLeft; pWaitPrimary = primaryLimit
            pWaitStamp = System.currentTimeMillis()
            requestRender()
        }

        override fun onRetry(what: String) {
            pRetryStamp = System.currentTimeMillis()
            requestRender()
        }
    }

    /** Many callbacks can arrive in a burst: draw at most once per UI turn. */
    private fun requestRender() {
        if (renderPending.compareAndSet(false, true)) {
            runOnUiThread {
                renderPending.set(false)
                renderProgress()
            }
        }
    }

    private fun renderProgress() {
        if (!uploading) return
        val now = System.currentTimeMillis()
        val waiting = now - pWaitStamp < NOTICE_MS
        val retrying = now - pRetryStamp < RETRY_NOTICE_MS

        uploadStatus.text = when {
            cancelRequested -> getString(R.string.stopping)
            waiting && pWaitPrimary -> getString(R.string.wait_primary, formatDuration(pWaitSeconds))
            waiting -> getString(R.string.wait_secondary, formatDuration(pWaitSeconds))
            retrying -> getString(R.string.retrying)
            pScanning -> getString(R.string.phase_scanning, pScanFound)
            else -> phaseText()
        }

        val parts = ArrayList<String>()
        if (pFilesTotal > 0) parts.add(getString(R.string.detail_files, pFilesDone, pFilesTotal))
        if (pBytesTotal > 0) {
            parts.add(
                getString(
                    R.string.detail_bytes,
                    Formatter.formatShortFileSize(this, pBytesDone), Formatter.formatShortFileSize(this, pBytesTotal)
                )
            )
        }
        val speed = pSpeed
        if (speed > 0 && !waiting) {
            parts.add(getString(R.string.detail_speed, Formatter.formatShortFileSize(this, speed)))
            val left = pBytesTotal - pBytesDone
            if (left > 0) parts.add(getString(R.string.detail_eta, formatDuration(left / speed + 1)))
        }
        uploadDetail.text = parts.joinToString("  ·  ")

        val phase = pPhase
        val measurable = pBytesTotal > 0 || (pFilesTotal > 0 && pFilesDone > 0)
        uploadProgress.isIndeterminate = pScanning || phase == null || !measurable
        if (!uploadProgress.isIndeterminate) {
            uploadProgress.progress = when {
                pBytesTotal > 0 -> (pBytesDone * 1000 / pBytesTotal).coerceIn(0, 1000).toInt()
                else -> (pFilesDone * 1000L / pFilesTotal).coerceIn(0, 1000).toInt()
            }
        }

        // A wait or retry notice fades by itself; draw once more when it is due to.
        ui.removeCallbacks(refreshNotice)
        if (waiting || retrying) ui.postDelayed(refreshNotice, NOTICE_MS + 100)
    }

    private fun phaseText(): String {
        val total = maxOf(pTotal, pCur)
        return when (pPhase) {
            null -> getString(R.string.phase_checking)
            Phase.CHECKING_BRANCH -> getString(R.string.phase_checking)
            Phase.PREPARING -> getString(R.string.phase_preparing)
            Phase.COMPARING ->
                if (pTotal > 0 && pCur > 0) getString(R.string.phase_comparing_n, pCur, pTotal)
                else getString(R.string.phase_comparing)
            Phase.FIRST_COMMIT -> getString(R.string.phase_first_commit)
            Phase.UPLOADING ->
                if (total > 1) getString(R.string.phase_uploading_n, pCur, total)
                else getString(R.string.phase_uploading)
            Phase.COMMITTING ->
                if (pTotal > 1) getString(R.string.phase_committing_n, pCur, pTotal)
                else getString(R.string.phase_committing)
        }
    }

    private fun runUpload(session: ReviewSession, files: List<UploadFile>, extras: Extras, authToken: String) {
        val repo = session.repo
        val branch = session.branch
        try {
            if (cancelRequested) throw UploadCancelled()
            val up = Uploader(EngineConfig(), authToken, listener)
            uploader = up
            if (cancelRequested) up.cancel()
            val result = up.run(UploadRequest(repo, branch, session.message, files, cacheDir))
            val verified = verifyHead(repo, result)
            runOnUiThread { uploadFinished(repo, result, session.scan, extras, verified) }
        } catch (e: UploadCancelled) {
            runOnUiThread { uploadCancelled(e.committedFiles) }
        } catch (e: UploadError) {
            runOnUiThread { uploadFailed(repo, branch, e) }
        } catch (e: Throwable) {
            runOnUiThread { uploadCrashed(e) }
        } finally {
            uploader = null
        }
    }

    /** True when GitHub now reports the last commit of this upload as the head of the branch. */
    private fun verifyHead(repo: String, result: UploadResult): Boolean {
        val last = result.commits.lastOrNull() ?: return false
        val a = api ?: return false
        return try {
            a.branchHead(repo, result.branch) == last.sha
        } catch (e: Exception) {
            false
        }
    }

    private fun endUpload() {
        uploading = false
        reviewing = false
        ui.removeCallbacks(refreshNotice)
        setUploadUI(false)
        uploadProgress.isIndeterminate = false
        uploadProgress.progress = 0
        uploadDetail.text = ""
        scrollStatusIntoView()
        // Files shared while the screen was busy join the selection as soon as it is idle again.
        ui.post { if (flipper.displayedChild == 2 && !uploading && !reviewing) consumePendingShared() }
    }

    private fun uploadFinished(repo: String, engine: UploadResult, scan: ScanResult, extras: Extras, verified: Boolean) {
        endUpload()
        uploadStatus.text = getString(R.string.done)
        // The engine reports what it skipped itself; add what the review had already set aside.
        val r = UploadResult(
            engine.commits, engine.filesUploaded, engine.bytesUploaded,
            engine.skipped + extras.skipped, engine.branch, engine.elapsedMs
        )
        val sb = StringBuilder()
        if (r.filesUploaded > 0) {
            sb.append(getString(R.string.res_uploaded, r.filesUploaded, r.commits.size)).append('\n')
        } else if (r.skipped.isEmpty() || r.count(SkipReason.UNCHANGED) > 0) {
            sb.append(getString(R.string.res_nothing_new)).append('\n')
        }
        val unchanged = r.count(SkipReason.UNCHANGED)
        if (unchanged > 0) sb.append(getString(R.string.res_unchanged, unchanged)).append('\n')
        appendProblem(sb, R.string.res_left_out_secrets, extras.leftOutSecrets)
        appendProblem(sb, R.string.res_left_out, extras.leftOut)
        appendProblem(sb, R.string.res_too_large, r.paths(SkipReason.TOO_LARGE))
        appendProblem(sb, R.string.res_invalid, r.paths(SkipReason.INVALID_PATH))
        appendProblem(sb, R.string.res_duplicate, r.paths(SkipReason.DUPLICATE_PATH))
        appendProblem(sb, R.string.res_unreadable, r.paths(SkipReason.UNREADABLE))
        appendProblem(sb, R.string.res_folders_unreadable, scan.unreadableFolders)
        if (scan.gitFoldersSkipped > 0) sb.append(getString(R.string.res_git_skipped, scan.gitFoldersSkipped)).append('\n')
        if (r.filesUploaded > 0) {
            sb.append(getString(R.string.res_where, repo, r.branch, formatDuration(r.elapsedMs / 1000)))
            val last = r.commits.lastOrNull()
            if (last != null) sb.append('\n').append(last.url)
            if (verified && last != null) {
                sb.append('\n').append(getString(R.string.res_verified, r.branch, last.sha.take(7)))
            }
        }
        uploadResult.text = sb.toString().trim()
        toast(R.string.pushed_success)
    }

    private fun appendProblem(sb: StringBuilder, headline: Int, names: List<String>) {
        if (names.isEmpty()) return
        sb.append(getString(headline, names.size)).append('\n')
        for (n in names.take(MAX_NAMES)) sb.append("   • ").append(n).append('\n')
        if (names.size > MAX_NAMES) sb.append("   ").append(getString(R.string.sel_more, names.size - MAX_NAMES)).append('\n')
    }

    private fun uploadCancelled(committed: Int) {
        endUpload()
        uploadStatus.text = getString(R.string.upload_stopped)
        uploadResult.text =
            if (committed > 0) getString(R.string.stopped_partial, committed) else getString(R.string.stopped_nothing)
    }

    private fun uploadFailed(repo: String, branch: String, e: UploadError) {
        endUpload()
        uploadStatus.text = getString(R.string.upload_failed_title)
        val sb = StringBuilder()
        sb.append(
            when (e.kind) {
                ErrorKind.NOTHING_TO_UPLOAD -> getString(R.string.nothing_to_upload)
                ErrorKind.BRANCH_NOT_FOUND -> getString(R.string.branch_not_found, branch, repo)
                ErrorKind.BRANCH_CHECK -> getString(R.string.branch_check_failed, e.code, e.detail)
                ErrorKind.FIRST_COMMIT -> getString(R.string.first_commit_failed, e.code, e.detail)
                ErrorKind.TREE -> getString(R.string.tree_failed, e.code, e.detail)
                ErrorKind.COMMIT -> getString(R.string.commit_failed, e.code, e.detail)
                ErrorKind.BRANCH_UPDATE -> getString(R.string.branch_update_failed, e.code, e.detail)
                ErrorKind.UPLOAD -> getString(R.string.upload_failed, e.path, e.code, e.detail)
                ErrorKind.RATE_LIMIT -> {
                    val wait = maxOf(60L, e.resetEpochSec - System.currentTimeMillis() / 1000)
                    getString(R.string.err_rate_limit, formatDuration(wait))
                }
                ErrorKind.NETWORK -> getString(R.string.err_network, e.detail)
            }
        )
        if (e.code == 401) sb.append('\n').append(getString(R.string.hint_token))
        else if ((e.code == 403 || e.code == 404) && e.kind != ErrorKind.BRANCH_NOT_FOUND) {
            sb.append('\n').append(getString(R.string.hint_permissions))
        }
        if (e.committedFiles > 0) sb.append('\n').append(getString(R.string.err_partial, e.committedFiles))
        uploadResult.text = sb.toString()
    }

    private fun uploadCrashed(e: Throwable) {
        endUpload()
        uploadStatus.text = getString(R.string.upload_failed_title)
        uploadResult.text = getString(R.string.err_unexpected, e.message ?: e.javaClass.simpleName)
    }

    private fun requestStop() {
        if ((!uploading && !reviewing) || cancelRequested) return
        cancelRequested = true
        cancelBtn.isEnabled = false
        uploader?.cancel()
        planner?.cancel()
        if (reviewing) renderReviewProgress() else renderProgress()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.stop_title))
            .setMessage(getString(R.string.stop_message))
            .setPositiveButton(getString(R.string.stop_btn)) { _, _ -> requestStop() }
            .setNegativeButton(getString(R.string.keep_going), null)
            .show()
    }

    // ---------------------------------------------------------------- new branch

    private fun showBranchControls(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        branchSpinner.visibility = v
        newBranchBtn.visibility = v
    }

    private fun promptNewBranch() {
        val repo = currentRepo()
        val from = currentBranch()
        if (repo == null || api == null || repoIsEmpty || from.isEmpty()) {
            toast(R.string.pick_branch_toast)
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.new_branch_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setSingleLine(true)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(
            input,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_branch_title))
            .setMessage(getString(R.string.new_branch_msg, from))
            .setView(box)
            .setPositiveButton(getString(R.string.create_btn), null)
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .create()
        dialog.setOnShowListener {
            // Set here (not in the builder) so an invalid name keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!BranchNames.isValid(name)) {
                    input.error = getString(R.string.err_branch_name)
                } else {
                    dialog.dismiss()
                    createBranch(repo, from, name)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun createBranch(repo: String, from: String, name: String) {
        val a = api ?: return
        repoProgress.visibility = View.VISIBLE
        continueBtn.isEnabled = false
        newBranchBtn.isEnabled = false
        showStatus(repoStatus, getString(R.string.creating_branch), false)
        thread(name = "gitdrop-new-branch") {
            try {
                a.createBranch(repo, from, name)
                runOnUiThread { branchCreated(repo, name) }
            } catch (e: Throwable) {
                runOnUiThread { branchCreateFailed(e) }
            }
        }
    }

    private fun branchCreated(repo: String, name: String) {
        newBranchBtn.isEnabled = true
        Toast.makeText(this, getString(R.string.branch_created, name), Toast.LENGTH_LONG).show()
        if (currentRepo() != repo) { // another repository was picked in the meantime
            repoProgress.visibility = View.GONE
            continueBtn.isEnabled = true
            showStatus(repoStatus, "", false)
            return
        }
        branchToSelect = name
        loadBranches(repo) // the reloaded list contains the new branch, which gets selected
    }

    private fun branchCreateFailed(e: Throwable) {
        newBranchBtn.isEnabled = true
        repoProgress.visibility = View.GONE
        continueBtn.isEnabled = true
        showStatus(repoStatus, failureText(e, R.string.err_create_branch), true)
    }

    // ---------------------------------------------------------------- "Share to GitDrop"

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        try {
            if (action == Intent.ACTION_SEND) {
                val one = streamExtra(intent)
                if (one != null) uris.add(one)
            } else {
                uris.addAll(streamListExtra(intent))
            }
            val clip = intent.clipData
            if (uris.isEmpty() && clip != null) {
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri
                    if (u != null) uris.add(u)
                }
            }
        } catch (e: Exception) {
            // A malformed share from another app must never crash GitDrop.
        }
        if (uris.isEmpty()) {
            toast(R.string.shared_unsupported)
            return
        }
        pendingShared.addAll(uris)
        if (api != null && flipper.displayedChild == 2 && !uploading && !reviewing) {
            consumePendingShared()
        } else {
            Toast.makeText(this, getString(R.string.shared_waiting, pendingShared.size), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> {
        val list: ArrayList<Uri>? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        return list ?: emptyList()
    }

    /** Shared files join the selection once the person is signed in and on the selection screen. */
    private fun consumePendingShared() {
        if (pendingShared.isEmpty()) return
        val uris = ArrayList<Uri>(pendingShared)
        pendingShared.clear()
        addPickedFiles(uris)
        Toast.makeText(this, getString(R.string.shared_added, uris.size), Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- review before upload

    private fun wireReviewScreen() {
        reviewBackBtn.setOnClickListener { leaveReview() }
        reviewUploadBtn.setOnClickListener { onReviewUpload() }
        filterAll.setOnClickListener { setReviewFilter(RowFilter.ALL) }
        filterNew.setOnClickListener { setReviewFilter(RowFilter.NEW) }
        filterChanged.setOnClickListener { setReviewFilter(RowFilter.CHANGED) }
        filterFlagged.setOnClickListener { setReviewFilter(RowFilter.FLAGGED) }
        reviewSelectAll.setOnClickListener { setShownChecked(true) }
        reviewSelectNone.setOnClickListener { setShownChecked(false) }
        reviewList.setOnItemClickListener { _, _, position, _ ->
            val row = shownRows.getOrNull(position - reviewList.headerViewsCount) ?: return@setOnItemClickListener
            if (row.entry.status == FileStatus.UNCHANGED) return@setOnItemClickListener
            row.checked = !row.checked
            reviewAdapter?.notifyDataSetChanged()
            updateReviewCounts()
        }
    }

    private fun runReview(
        repo: String, branch: String, message: String, prefix: String, selection: List<Sel>, authToken: String
    ) {
        try {
            val scan = scanner.scan(selection, prefix, { cancelRequested }) { found ->
                pScanFound = found
                requestReviewRender()
            }
            if (scan.files.isEmpty() && scan.unreadableFolders.isEmpty()) throw UploadError(ErrorKind.NOTHING_TO_UPLOAD)
            if (cancelRequested) throw UploadCancelled()

            val p = ChangePlanner(authToken, repo, branch, scan.files)
            planner = p
            if (cancelRequested) p.cancel()
            val result = p.run(object : ReviewListener {
                override fun onStep(step: ReviewStep, current: Int, total: Int) {
                    pScanning = false
                    rvStep = step
                    rvCur = current
                    rvTotal = total
                    requestReviewRender()
                }
            })
            runOnUiThread { reviewReady(ReviewSession(repo, branch, message, scan, result)) }
        } catch (e: UploadCancelled) {
            runOnUiThread { reviewCancelled() }
        } catch (e: UploadError) {
            runOnUiThread { uploadFailed(repo, branch, e) }
        } catch (e: Throwable) {
            runOnUiThread { uploadCrashed(e) }
        } finally {
            planner = null
        }
    }

    /** Many callbacks can arrive in a burst: draw at most once per UI turn. */
    private fun requestReviewRender() {
        if (renderPending.compareAndSet(false, true)) {
            runOnUiThread {
                renderPending.set(false)
                renderReviewProgress()
            }
        }
    }

    private fun renderReviewProgress() {
        if (!reviewing) return
        uploadStatus.text = when {
            cancelRequested -> getString(R.string.stopping)
            pScanning -> getString(R.string.phase_scanning, pScanFound)
            else -> when (rvStep) {
                ReviewStep.CHECKING_BRANCH -> getString(R.string.phase_checking)
                ReviewStep.COMPARING -> getString(R.string.phase_comparing)
                ReviewStep.ANALYZING ->
                    if (rvTotal > 0) getString(R.string.phase_analyzing_n, rvCur, rvTotal)
                    else getString(R.string.phase_preparing)
            }
        }
        uploadDetail.text = ""
        val determinate = !pScanning && rvStep == ReviewStep.ANALYZING && rvTotal > 0
        uploadProgress.isIndeterminate = !determinate
        if (determinate) uploadProgress.progress = (rvCur * 1000L / rvTotal).coerceIn(0, 1000).toInt()
    }

    private fun reviewReady(session: ReviewSession) {
        endUpload()
        uploadStatus.text = ""
        uploadResult.text = ""
        review = session
        reviewRows.clear()
        for (e in session.result.entries) {
            // Identical files have nothing to upload; suspicious files start unticked.
            reviewRows.add(ReviewRow(e, e.status != FileStatus.UNCHANGED && !e.flagged))
        }
        reviewFilter = RowFilter.ALL
        bindReviewHeader(session)
        applyReviewFilter()
        reviewList.setSelection(0)
        flipper.displayedChild = 3
    }

    private fun reviewCancelled() {
        endUpload()
        uploadStatus.text = getString(R.string.upload_stopped)
        uploadResult.text = getString(R.string.stopped_nothing)
    }

    private fun clearReviewState() {
        review = null
        reviewRows.clear()
        shownRows = emptyList()
        reviewAdapter?.notifyDataSetChanged()
    }

    private fun leaveReview() {
        flipper.displayedChild = 2 // hide the list first, then drop its data
        clearReviewState()
        consumePendingShared()
    }

    /** Fills the parts of the review header that do not change while the person ticks files. */
    private fun bindReviewHeader(s: ReviewSession) {
        val r = s.result
        val newCount = r.entries.count { it.status == FileStatus.NEW }
        val changedCount = r.entries.count { it.status == FileStatus.CHANGED }
        val sameCount = r.entries.count { it.status == FileStatus.UNCHANGED }
        val flaggedCount = r.entries.count { it.flagged }

        reviewCounts.text =
            if (r.comparison == Comparison.UNAVAILABLE && r.entries.isNotEmpty()) getString(R.string.review_counts_unknown)
            else getString(R.string.review_counts, newCount, changedCount, sameCount)

        // Files that can never be uploaded, and what could not be checked.
        val notes = StringBuilder()
        fun problem(headline: Int, reason: SkipReason) =
            appendProblem(notes, headline, r.skipped.filter { it.reason == reason }.map { it.path })
        problem(R.string.res_too_large, SkipReason.TOO_LARGE)
        problem(R.string.res_invalid, SkipReason.INVALID_PATH)
        problem(R.string.res_duplicate, SkipReason.DUPLICATE_PATH)
        problem(R.string.res_unreadable, SkipReason.UNREADABLE)
        appendProblem(notes, R.string.res_folders_unreadable, s.scan.unreadableFolders)
        if (s.scan.gitFoldersSkipped > 0) {
            notes.append(getString(R.string.res_git_skipped, s.scan.gitFoldersSkipped)).append('\n')
        }
        if (r.notScanned > 0) notes.append(getString(R.string.review_not_scanned, r.notScanned)).append('\n')
        val noteText = notes.toString().trim()
        reviewNotes.text = noteText
        reviewNotes.visibility = if (noteText.isEmpty()) View.GONE else View.VISIBLE

        if (flaggedCount > 0) {
            reviewSecretBanner.text = getString(R.string.review_secrets_banner, flaggedCount)
            reviewSecretBanner.visibility = View.VISIBLE
        } else {
            reviewSecretBanner.visibility = View.GONE
        }
        if (r.identity.isNotEmpty()) {
            reviewIdentityBanner.text = r.identity.joinToString("\n\n") {
                getString(R.string.review_identity_banner, it.path, it.localId, it.remoteId)
            }
            reviewIdentityBanner.visibility = View.VISIBLE
        } else {
            reviewIdentityBanner.visibility = View.GONE
        }

        filterAll.text = getString(R.string.filter_all, r.entries.size)
        filterNew.text = getString(R.string.filter_new, newCount)
        filterChanged.text = getString(R.string.filter_changed, changedCount)
        filterFlagged.text = getString(R.string.filter_flagged, flaggedCount)
        filterNew.visibility = if (newCount > 0) View.VISIBLE else View.GONE
        filterChanged.visibility = if (changedCount > 0) View.VISIBLE else View.GONE
        filterFlagged.visibility = if (flaggedCount > 0) View.VISIBLE else View.GONE
    }

    private fun setReviewFilter(f: RowFilter) {
        reviewFilter = f
        applyReviewFilter()
    }

    private fun applyReviewFilter() {
        shownRows = when (reviewFilter) {
            RowFilter.ALL -> reviewRows.toList()
            RowFilter.NEW -> reviewRows.filter { it.entry.status == FileStatus.NEW }
            RowFilter.CHANGED -> reviewRows.filter { it.entry.status == FileStatus.CHANGED }
            RowFilter.FLAGGED -> reviewRows.filter { it.entry.flagged }
        }
        filterAll.isSelected = reviewFilter == RowFilter.ALL
        filterNew.isSelected = reviewFilter == RowFilter.NEW
        filterChanged.isSelected = reviewFilter == RowFilter.CHANGED
        filterFlagged.isSelected = reviewFilter == RowFilter.FLAGGED
        reviewEmpty.visibility = if (shownRows.isEmpty()) View.VISIBLE else View.GONE
        reviewAdapter?.notifyDataSetChanged()
        updateReviewCounts()
    }

    /** Ticks or unticks what is on screen. Suspicious files are never ticked in bulk (only from the Flagged view). */
    private fun setShownChecked(value: Boolean) {
        for (row in shownRows) {
            if (row.entry.status == FileStatus.UNCHANGED) continue
            if (value && row.entry.flagged && reviewFilter != RowFilter.FLAGGED) continue
            row.checked = value
        }
        reviewAdapter?.notifyDataSetChanged()
        updateReviewCounts()
    }

    private fun updateReviewCounts() {
        if (review == null) return
        val uploadable = reviewRows.count { it.entry.status != FileStatus.UNCHANGED }
        val chosen = reviewRows.count { it.checked }
        reviewSummary.text =
            if (reviewRows.isNotEmpty() && uploadable == 0) getString(R.string.review_all_current)
            else getString(R.string.review_summary, chosen, uploadable)
        reviewUploadBtn.text = getString(R.string.review_upload_btn, chosen)
        reviewUploadBtn.isEnabled = chosen > 0
    }

    private fun onReviewUpload() {
        val s = review ?: return
        val chosen = reviewRows.filter { it.checked && it.entry.status != FileStatus.UNCHANGED }
        if (chosen.isEmpty()) return
        val flagged = chosen.count { it.entry.flagged }
        val identity = s.result.identity.isNotEmpty()
        if (flagged == 0 && !identity) {
            beginUpload(s, chosen)
            return
        }
        // Something deserves a second thought: ask once more, in plain words.
        val msg = StringBuilder()
        if (flagged > 0) msg.append(getString(R.string.confirm_flagged, flagged))
        if (identity) {
            if (msg.isNotEmpty()) msg.append("\n\n")
            msg.append(getString(R.string.confirm_identity))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_title))
            .setMessage(msg.toString())
            .setPositiveButton(getString(R.string.confirm_yes)) { _, _ -> beginUpload(s, chosen) }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show()
    }

    private fun kindLabel(kind: SecretKind): String = getString(
        when (kind) {
            SecretKind.TOKEN -> R.string.kind_token
            SecretKind.PRIVATE_KEY -> R.string.kind_private_key
            SecretKind.PASSWORD -> R.string.kind_password
            SecretKind.KEY_FILE -> R.string.kind_key_file
            SecretKind.ENV_FILE -> R.string.kind_env_file
            SecretKind.CREDENTIALS_FILE -> R.string.kind_credentials
        }
    )

    /** "⚠ API key or token (GitHub) · line 12": what looked suspicious, never the secret itself. */
    private fun findingText(findings: List<SecretFinding>): String {
        val parts = ArrayList<String>()
        for (f in findings.take(2)) {
            val sb = StringBuilder(kindLabel(f.kind))
            if (f.provider.isNotEmpty()) sb.append(" (").append(f.provider).append(')')
            if (f.line > 0) sb.append(" · ").append(getString(R.string.finding_line, f.line))
            parts.add(sb.toString())
        }
        if (findings.size > 2) parts.add("…")
        return "⚠ " + parts.joinToString("\n")
    }

    private inner class ReviewAdapter : BaseAdapter() {
        override fun getCount(): Int = shownRows.size

        override fun getItem(position: Int): Any = shownRows.getOrNull(position) ?: position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_review, parent, false)
            // The list can ask for a row that has just been removed (data and layout are updated in two steps).
            val row = shownRows.getOrNull(position) ?: return v
            val e = row.entry
            val check = v.findViewById<CheckBox>(R.id.reviewCheck)
            val name = v.findViewById<TextView>(R.id.reviewName)
            val meta = v.findViewById<TextView>(R.id.reviewMeta)
            val flag = v.findViewById<TextView>(R.id.reviewFlag)
            val badge = v.findViewById<TextView>(R.id.reviewBadge)

            name.text = e.path.substringAfterLast('/')
            val dir = e.path.substringBeforeLast('/', "")
            val size = Formatter.formatShortFileSize(this@MainActivity, e.size)
            meta.text = if (dir.isEmpty()) size else "$dir/  ·  $size"

            val selectable = e.status != FileStatus.UNCHANGED
            check.isChecked = row.checked
            check.isEnabled = selectable
            v.alpha = if (selectable) 1f else 0.55f

            if (e.flagged) {
                flag.text = findingText(e.findings)
                flag.visibility = View.VISIBLE
            } else {
                flag.visibility = View.GONE
            }

            when (e.status) {
                FileStatus.NEW -> {
                    badge.text = getString(R.string.badge_new)
                    badge.setTextColor(getColor(R.color.ok))
                }
                FileStatus.CHANGED -> {
                    badge.text = getString(R.string.badge_changed)
                    badge.setTextColor(getColor(R.color.warnText))
                }
                FileStatus.UNCHANGED -> {
                    badge.text = getString(R.string.badge_same)
                    badge.setTextColor(getColor(R.color.textHint))
                }
                FileStatus.UNKNOWN -> badge.text = ""
            }
            return v
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Text of API failures on the first two screens. [apiFormat] takes (HTTP code, GitHub's message). */
    private fun failureText(e: Throwable, apiFormat: Int): String = when (e) {
        is ApiError ->
            if (e.code == 401) getString(R.string.key_rejected, e.code, e.detail)
            else getString(apiFormat, e.code, e.detail)
        is UploadError ->
            if (e.kind == ErrorKind.RATE_LIMIT) {
                val wait = maxOf(60L, e.resetEpochSec - System.currentTimeMillis() / 1000)
                getString(R.string.err_rate_limit_short, formatDuration(wait))
            } else {
                getString(R.string.network_error, e.detail.ifEmpty { e.message ?: "" })
            }
        else -> getString(R.string.network_error, e.message ?: e.javaClass.simpleName)
    }

    private fun showStatus(view: TextView, text: String, error: Boolean) {
        view.setTextColor(getColor(if (error) R.color.red else R.color.textHint))
        view.text = text
    }

    private fun toast(res: Int) = Toast.makeText(this, getString(res), Toast.LENGTH_LONG).show()

    private fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return when {
            s < 60 -> getString(R.string.dur_s, s)
            s < 3600 -> getString(R.string.dur_min_s, s / 60, s % 60)
            else -> getString(R.string.dur_h_min, s / 3600, (s % 3600) / 60)
        }
    }

    /** Removes scratch files an earlier run left behind if the app was killed mid-upload. */
    private fun cleanStaleTempFiles() {
        thread(name = "gitdrop-cleanup", isDaemon = true) {
            try {
                val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
                cacheDir.listFiles()?.forEach { f: File ->
                    if (f.name.startsWith("gitdrop") && f.name.endsWith(".tmp") && f.lastModified() < cutoff) f.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val REQ_FILES = 1
        const val REQ_FOLDER = 2
        const val MAX_LISTED = 40
        const val MAX_NAMES = 5
        const val FILTER_FROM = 8
        const val NOTICE_MS = 2500L
        const val RETRY_NOTICE_MS = 5000L
        const val TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=repo&description=GitDrop"
    }
}

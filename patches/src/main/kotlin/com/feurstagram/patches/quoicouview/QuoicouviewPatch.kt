package com.feurstagram.patches.quoicouview

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val QUOICOUVIEW = "Lcom/feurstagram/extension/Quoicouview;"
private const val ROW = "Lcom/feurstagram/extension/StoriesTrayRow;"
private const val ROW_MODEL = "Lcom/feurstagram/extension/StoriesTrayRow\$Model;"
private const val ROW_HOLDER = "Lcom/feurstagram/extension/StoriesTrayRow\$Holder;"

// Instagram's list adapter ("IgRecyclerViewAdapter", the name survives in a
// diagnostics string of its constructor). It is built from a builder holding
// its row types ("item definitions"), and the inbox is one of them.
internal object IgRecyclerViewAdapterFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("IgRecyclerViewAdapter.mDefinitions"),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

// The stories tray's tap handler: before opening the viewer it checks that the
// feed fragment is visible, and gives up with these two log events otherwise.
internal object StoryTrayTapFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("fragment_not_visible", "story_pog_blocked"),
)

// The story viewer launcher's environment check, run once the tapped story is
// loaded: the feed fragment must be resumed and the feed visible, or the launch
// is dropped and reported as "ENVIRONMENT_RESUMED" with these two fields.
internal object StoryLaunchEnvironmentFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf("ENVIRONMENT_RESUMED", "fragment_resumed", "feed_visible"),
)

// The feed tray controller ("MainFeedReelTrayController"), by its story launch
// ("preloadAndLaunchViewer"; both names survive in a tracing section).
internal object StoryTrayLaunchFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MainFeedReelTrayController.preloadAndLaunchViewer"),
)

// The story viewer launcher's in-tab path: it pushes the viewer fragment into
// the current tab under this back stack name.
internal object StoryViewerPushFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("ReelViewerFragment.BACK_STACK_NAME"),
)

private const val STORY_VIEWER = "Linstagram/features/stories/fragment/ReelViewerFragment;"

// The Direct inbox fragment, by its result handling ("DirectInboxController",
// the name survives in a tracing section).
internal object DirectInboxResultFingerprint : Fingerprint(
    name = "onActivityResult",
    strings = listOf("DirectInboxController.onActivityResult"),
)

// The main tab controller's tab change ("MainTabControllerImpl.onTabChanged",
// the name survives in a tracing section): among other things it turns the swipe
// navigation's panels on and off for the new tab, the camera only for Home.
internal object MainTabChangedFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MainTabControllerImpl.onTabChanged: new "),
)

// A tab page of the main pager, which reads whether it loads its content as soon
// as it is created ("IgSwipeableTabHostFragment" survives in a tracing section).
internal object TabHostCreateFingerprint : Fingerprint(
    name = "onCreate",
    strings = listOf("IgSwipeableTabHostFragment.onCreate", "EAGER_FRAGMENT_LOADING"),
)

// The enum of the main navigation's variants, by its constants' names.
internal object NavigationVariantFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("NO_OVERRIDE", "REELS_SECOND", "FORCE_OPT_OUT"),
)

private const val SWIPE_CONTAINER = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"

private const val EXTENSION_CLASSES = "Lcom/feurstagram/"

internal val Method.params get() = parameterTypes.map { it.toString() }

internal fun methodRef(reference: Any?) = reference as? MethodReference

@Suppress("unused")
val quoicouviewPatch = bytecodePatch(
    name = "Quoicouview mode",
    description = "Lets the experimental Quoicouview mode put the stories tray at the top of the " +
        "Direct inbox, as a row of the inbox list, open stories from there the way the feed " +
        "opens them, and reach the story camera from Messages as from Home.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // --- The adapter, its builder, its row-type and model API -------------------

        val adapterInit = IgRecyclerViewAdapterFingerprint.method
        val adapterType = IgRecyclerViewAdapterFingerprint.classDef.type
        val builderType = adapterInit.params.single()
        val builder = classDefBy(builderType)

        // builder.add(definition): the one single-argument void method taking an
        // Instagram type (its sibling takes a java.util.Collection, the
        // constructor a LayoutInflater).
        val addDefinition = builder.methods.singleOrNull {
            it.name != "<init>" && it.returnType == "V" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString().let { type ->
                    !type.startsWith("Ljava/") && !type.startsWith("Landroid/")
                }
        } ?: throw PatchException("Adapter builder has no add-definition method")
        val definitionType = addDefinition.params.single()
        val definition = classDefBy(definitionType)

        val createRow = definition.methods.singleOrNull {
            it.params == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;")
        } ?: throw PatchException("Row definition has no create method")
        val holderType = createRow.returnType
        val modelClass = definition.methods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Class;"
        } ?: throw PatchException("Row definition has no model-class method")
        val bindRow = definition.methods.singleOrNull {
            it.returnType == "V" && it.parameterTypes.size == 2 && it.params[0] == holderType
        } ?: throw PatchException("Row definition has no bind method")
        val modelType = bindRow.params[1]

        val holder = classDefBy(holderType)
        if (holder.methods.none { it.name == "<init>" && it.params == listOf("Landroid/view/View;") }) {
            throw PatchException("View holder has no (View) constructor")
        }
        val itemView = holder.fields.singleOrNull {
            it.type == "Landroid/view/View;" && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("View holder has no item view field")

        // --- The stories row's holder: the holder base is abstract, so a subclass ---------

        mutableClassDefBy(ROW_HOLDER).apply {
            setSuperClass(holderType)
            methods.removeAll { it.name == "<init>" }
            addBridge(
                "<init>", listOf("Landroid/view/View;"), "V", registers = 2,
                """
                    invoke-direct { p0, p1 }, $holderType-><init>(Landroid/view/View;)V
                    return-void
                """,
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            )
        }

        // --- The stories row type: StoriesTrayRow becomes a row definition ------------

        mutableClassDefBy(ROW).apply {
            setSuperClass(definitionType)
            addBridge(
                createRow.name, createRow.params, holderType, registers = 5,
                """
                    invoke-static { p1, p2 }, $QUOICOUVIEW->createStoriesRow(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Landroid/view/View;
                    move-result-object v0
                    new-instance v1, $ROW_HOLDER
                    invoke-direct { v1, v0 }, $ROW_HOLDER-><init>(Landroid/view/View;)V
                    return-object v1
                """,
            )
            addBridge(
                modelClass.name, emptyList(), "Ljava/lang/Class;", registers = 2,
                """
                    const-class v0, $ROW_MODEL
                    return-object v0
                """,
            )
            addBridge(
                bindRow.name, bindRow.params, "V", registers = 4,
                """
                    iget-object v0, p1, $holderType->${itemView.name}:Landroid/view/View;
                    invoke-static { v0 }, $QUOICOUVIEW->bindStoriesRow(Landroid/view/View;)V
                    return-void
                """,
            )
        }

        // --- Its model: StoriesTrayRow.Model becomes a list model ----------------------

        mutableClassDefBy(ROW_MODEL).apply {
            interfaces.add(modelType)
            // getKey() keeps its name in Instagram's interface; the content-equality
            // check of the interface it extends is obfuscated: bridge it.
            for (method in abstractMethods(modelType)) {
                if (method.name == "getKey") continue
                if (method.returnType != "Z" || method.params != listOf("Ljava/lang/Object;")) {
                    throw PatchException("Unexpected list-model method ${method.name}${method.params}")
                }
                addBridge(
                    method.name, method.params, "Z", registers = 3,
                    """
                        invoke-virtual { p0, p1 }, $ROW_MODEL->sameContentAs(Ljava/lang/Object;)Z
                        move-result v0
                        return v0
                    """,
                )
            }
        }

        // --- Register the row type in every adapter built ------------------------------

        adapterInit.apply {
            // At the very top of the constructor no local is live yet: v0 and v1
            // are free. The builder is copied down to v1 because the parameter
            // registers of this large method are out of a plain invoke's reach.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $QUOICOUVIEW->storiesRowDefinition()Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :keep
                    check-cast v0, $definitionType
                    move-object/from16 v1, p1
                    invoke-virtual { v1, v0 }, $builderType->${addDefinition.name}($definitionType)V
                """,
                ExternalLabel("keep", instructions[0]),
            )
        }

        // --- Hand the inbox's list updates to the extension -----------------------------

        igAdapterApply().addInstructions(
            0,
            "invoke-static { p0, p1 }, $QUOICOUVIEW->onListUpdate(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // --- The layout the mode stands on: a tab pager, with a Messages tab -------------

        // Every check of the navigation variant starts from the override it reads
        // first (a developer option): while the mode is on, that override says
        // REELS_SECOND, the variant with the pager and a Messages tab.
        val variantType = NavigationVariantFingerprint.classDef.type
        val variantOverrides = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            classDef.methods.forEach { method ->
                if (method.returnType == variantType && AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.params == listOf("Lcom/instagram/common/session/UserSession;")
                ) variantOverrides += classDef.type to method
            }
        }
        val (overrideClass, overrideMethod) = variantOverrides.singleOrNull()
            ?: throw PatchException("Expected one navigation variant override, found ${variantOverrides.size}")
        mutableClassDefBy(overrideClass).methods.single {
            it.name == overrideMethod.name && it.params == overrideMethod.params
        }.apply {
            addInstructionsWithLabels(
                0,
                """
                    const-class v0, $variantType
                    invoke-static { v0 }, $QUOICOUVIEW->forcedNavigationVariant(Ljava/lang/Class;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :server
                    check-cast v0, $variantType
                    return-object v0
                """,
                ExternalLabel("server", instructions[0]),
            )
        }

        // --- The feed page loads even if it is not the one the app starts on -------------

        TabHostCreateFingerprint.method.apply {
            val eagerAt = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "EAGER_FRAGMENT_LOADING"
            }
            val read = instructions.drop(eagerAt).first { it.opcode == Opcode.INVOKE_VIRTUAL }
            val result = instructions[read.location.index + 1]
            if (result.opcode != Opcode.MOVE_RESULT ||
                methodRef((read as ReferenceInstruction).reference)?.name != "getBoolean"
            ) throw PatchException("Tab page no longer reads its loading flag")
            val eager = (result as OneRegisterInstruction).registerA
            val arguments = (read as FiveRegisterInstruction).registerC
            addInstructions(
                result.location.index + 1,
                """
                    invoke-static { v$eager, v$arguments }, $QUOICOUVIEW->loadsTabEagerly(ZLandroid/os/Bundle;)Z
                    move-result v$eager
                """,
            )
        }

        // --- Let a tap on the tray open the story from the inbox -------------------------

        // Both checks ask whether the feed is the surface the user is looking at;
        // with the tray in the inbox, the inbox is. Each answer goes through
        // Quoicouview.isStoryTrayVisible, which says yes when the tray is on screen.
        StoryLaunchEnvironmentFingerprint.method.apply {
            val returns = instructions.filter { it.opcode == Opcode.RETURN }
            if (returns.isEmpty()) throw PatchException("Story launch environment check has no return")
            returns.reversed().forEach { ret ->
                val register = (ret as OneRegisterInstruction).registerA
                addInstructions(
                    ret.location.index,
                    """
                        invoke-static { v$register }, $QUOICOUVIEW->isStoryTrayVisible(Z)Z
                        move-result v$register
                    """,
                )
            }
        }

        StoryTrayTapFingerprint.method.apply {
            val checks = instructions.filter {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                        ref.definingClass == "Landroidx/fragment/app/Fragment;" && ref.name == "isVisible"
                    } == true
            }.map { instructions[it.location.index + 1] }
                .filter { it.opcode == Opcode.MOVE_RESULT }
            if (checks.isEmpty()) throw PatchException("Story tray tap no longer checks the feed's visibility")
            checks.reversed().forEach { result ->
                val register = (result as OneRegisterInstruction).registerA
                addInstructions(
                    result.location.index + 1,
                    """
                        invoke-static { v$register }, $QUOICOUVIEW->isStoryTrayVisible(Z)Z
                        move-result v$register
                    """,
                )
            }
        }

        // --- The tray controller lives with the page that shows its tray ----------------

        // The feed's tray controller follows the feed fragment's lifecycle: its
        // onResume, among others, plays the story viewer's closing animation back
        // into the tray and refreshes the tray, its onPause stops what runs. With
        // the tray in the inbox the feed never resumes, so the inbox's own resume
        // and pause are passed on to it, as the feed's are.
        val controllerType = StoryTrayLaunchFingerprint.classDef.type
        mutableClassDefBy(controllerType).apply {
            listOf("onResume", "onPause").forEach { name ->
                methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                    ?: throw PatchException("Tray controller has no $name()")
            }
            methods.filter { it.name == "<init>" }.ifEmpty { throw PatchException("Tray controller has no constructor") }
                .forEach { init ->
                    init.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                        init.addInstructions(
                            ret.location.index,
                            "invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->onTrayController(Ljava/lang/Object;)V",
                        )
                    }
                }
        }
        mutableClassDefBy(DirectInboxResultFingerprint.classDef.type).apply {
            listOf("onResume" to "trayControllerToResume", "onPause" to "trayControllerToPause").forEach { (name, hook) ->
                val method = methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                    ?: throw PatchException("Direct inbox fragment has no $name()")
                // After the page's own handling, as Instagram's fragments notify
                // their lifecycle listeners. Nothing is live at a return-void.
                method.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                    method.addInstructionsWithLabels(
                        ret.location.index,
                        """
                            invoke-static { }, $QUOICOUVIEW->$hook()Ljava/lang/Object;
                            move-result-object v0
                            if-eqz v0, :done
                            check-cast v0, $controllerType
                            invoke-virtual { v0 }, $controllerType->$name()V
                        """,
                        ExternalLabel("done", ret),
                    )
                }
            }
        }

        // --- The viewer goes over the inbox, not in place of it --------------------------

        // The launcher pushes the viewer into the current tab with a replace: the
        // page beneath is torn down, and built again when the viewer closes. Cheap
        // for the feed, not for the inbox, whose rebuild would hold the closing
        // animation back. A viewer opened from the tray in the inbox is added over
        // it instead, with the navigator's other commit, and the tray controller is
        // told its page is covered, then uncovered when that viewer goes: the
        // pause and resume the replace would have given it.

        // The tray's launches, by their parameters. Built in the launch itself up
        // to Instagram 453; 454 moved them into a private helper of the controller
        // it calls, so the controller's own direct calls are searched too.
        val trayLaunch = StoryTrayLaunchFingerprint.method
        val controller = mutableClassDefBy(controllerType)
        val launchSteps = listOf(trayLaunch) + trayLaunch.instructions.mapNotNull { instruction ->
            methodRef((instruction as? ReferenceInstruction)?.reference)?.takeIf {
                (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    it.definingClass == controllerType && it.name != "<init>"
            }
        }.distinct().mapNotNull { ref ->
            controller.methods.singleOrNull { it.name == ref.name && it.params == ref.parameterTypes.map { p -> p.toString() } }
        }
        val (launchStep, launchParams) = launchSteps.firstNotNullOfOrNull { step ->
            step.instructions.singleOrNull {
                it.opcode == Opcode.INVOKE_DIRECT_RANGE && methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                    ref.name == "<init>" && ref.parameterTypes.map { p -> p.toString() }.let { params ->
                        params.size == 9 && params[1] == "Ljava/lang/Class;" && params.drop(4).all { p -> p == "Z" }
                    }
                } == true
            }?.let { step to it }
        } ?: throw PatchException("Story tray launch no longer builds its launch parameters")
        val params = (launchParams as RegisterRangeInstruction).startRegister
        launchStep.addInstructions(
            launchParams.location.index + 1,
            "invoke-static/range { v$params .. v$params }, $QUOICOUVIEW->onTrayLaunch(Ljava/lang/Object;)V",
        )

        val launcher = StoryViewerPushFingerprint.method
        val launcherType = StoryViewerPushFingerprint.classDef.type
        // The navigator: the class the back stack name is stored into.
        val nameStore = launcher.instructions.withIndex().singleOrNull { (index, instruction) ->
            instruction.opcode == Opcode.IPUT_OBJECT && index > 0 &&
                ((launcher.instructions[index - 1] as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string == "ReelViewerFragment.BACK_STACK_NAME"
        }?.value ?: throw PatchException("Story viewer push no longer names its back stack entry")
        val navigatorType = ((nameStore as ReferenceInstruction).reference as FieldReference).definingClass
        // Its commit: the last no-argument call on it.
        val commit = launcher.instructions.lastOrNull {
            it.opcode == Opcode.INVOKE_VIRTUAL && methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                ref.definingClass == navigatorType && ref.parameterTypes.isEmpty() && ref.returnType == "V"
            } == true
        } ?: throw PatchException("Story viewer push no longer commits")
        val replaceCommit = methodRef((commit as ReferenceInstruction).reference)!!
        val navigator = classDefBy(navigatorType)
        // Commits are one-liners into the navigator's core, with an operation:
        // the add one is the commit whose operation leads the core to add the
        // fragment (a transaction call with FragmentTransaction's OP_ADD, 1).
        fun Method.commitOperation(): Pair<FieldReference, MethodReference>? {
            val body = implementation?.instructions?.toList() ?: return null
            if (parameterTypes.isNotEmpty() || returnType != "V" || body.size != 3) return null
            val operation = (body[0] as? ReferenceInstruction)?.reference as? FieldReference ?: return null
            val core = methodRef((body[1] as? ReferenceInstruction)?.reference) ?: return null
            return if (body[0].opcode == Opcode.SGET_OBJECT && body[1].opcode == Opcode.INVOKE_DIRECT &&
                body[2].opcode == Opcode.RETURN_VOID && core.definingClass == navigatorType
            ) operation to core else null
        }
        val replaceMethod = navigator.methods.single { it.name == replaceCommit.name && it.parameterTypes.isEmpty() }
        val (replaceOperation, coreRef) = replaceMethod.commitOperation()
            ?: throw PatchException("Story viewer commit is not a navigator operation")
        val core = navigator.methods.single { it.name == coreRef.name && it.params == coreRef.parameterTypes.map { p -> p.toString() } }
        val coreBody = core.implementation!!.instructions.toList()
        fun addsFragment(operation: FieldReference) = coreBody.withIndex().any { (index, instruction) ->
            instruction.opcode == Opcode.SGET_OBJECT && (instruction as ReferenceInstruction).reference.toString() == operation.toString() &&
                coreBody.drop(index + 1).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }?.let { call ->
                    val ref = methodRef((call as ReferenceInstruction).reference)!!
                    val body = classDefBy(ref.definingClass).methods.singleOrNull {
                        it.name == ref.name && it.params == ref.parameterTypes.map { p -> p.toString() }
                    }?.implementation?.instructions?.toList() ?: return@let false
                    body.size >= 2 && body[0].opcode == Opcode.CONST_4 &&
                        (body[0] as NarrowLiteralInstruction).narrowLiteral == 1 &&
                        methodRef((body[1] as? ReferenceInstruction)?.reference)?.parameterTypes?.size == 4
                } == true
        }
        if (addsFragment(replaceOperation)) throw PatchException("Story viewer commit already adds")
        val addMethod = navigator.methods.singleOrNull { method ->
            method.commitOperation()?.let { (operation, ref) -> ref == coreRef && addsFragment(operation) } == true
        } ?: throw PatchException("Navigator has no add commit")
        if (!AccessFlags.PUBLIC.isSet(addMethod.accessFlags)) throw PatchException("Navigator add commit is not public")

        mutableClassDefBy(launcherType).apply {
            addBridge(
                "feurstagramCommitStoryViewer", listOf("Ljava/lang/Object;"), "V", registers = 2,
                """
                    invoke-static { }, $QUOICOUVIEW->stacksStoryViewer()Z
                    move-result v0
                    check-cast p0, $navigatorType
                    if-eqz v0, :replace
                    invoke-virtual { p0 }, $navigatorType->${addMethod.name}()V
                    invoke-static { }, $QUOICOUVIEW->trayControllerToCover()Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :covered
                    check-cast v0, $controllerType
                    invoke-virtual { v0 }, $controllerType->onPause()V
                    :covered
                    return-void
                    :replace
                    invoke-virtual { p0 }, $navigatorType->${replaceCommit.name}()V
                    return-void
                """,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            )
        }
        val navigatorRegister = (commit as FiveRegisterInstruction).registerC
        launcher.replaceInstruction(
            commit.location.index,
            "invoke-static { v$navigatorRegister }, $launcherType->feurstagramCommitStoryViewer(Ljava/lang/Object;)V",
        )
        // Which launch this is, from its parameters (the second argument),
        // before the method reuses that register.
        launcher.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $QUOICOUVIEW->onStoryViewerLaunch(Ljava/lang/Object;)V",
        )

        // The viewer itself: which one was stacked, and when it goes.
        mutableClassDefBy(STORY_VIEWER).apply {
            val create = methods.singleOrNull { it.name == "onCreate" && it.params == listOf("Landroid/os/Bundle;") }
                ?: throw PatchException("Story viewer has no onCreate")
            create.addInstructions(0, "invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->onStoryViewerCreated(Ljava/lang/Object;)V")
            val destroy = methods.singleOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
                ?: throw PatchException("Story viewer has no onDestroy")
            // At the end: by then the viewer has handed the tray its closing animation.
            destroy.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                destroy.addInstructionsWithLabels(
                    ret.location.index,
                    """
                        invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->trayControllerToUncover(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        if-eqz v0, :done
                        check-cast v0, $controllerType
                        invoke-virtual { v0 }, $controllerType->onResume()V
                    """,
                    ExternalLabel("done", ret),
                )
            }
        }

        // --- The camera, left of Messages as it is left of Home ---------------------------

        // The tab controller asks whether Home is the current tab before turning the
        // camera panel on (at each tab change) and before letting a swipe from the
        // main page reach it. Each "no" then asks, through a bridge added to the
        // controller, whether Messages is current and counts as the main page.
        val tabChanged = MainTabChangedFingerprint.method
        val tabControllerType = MainTabChangedFingerprint.classDef.type
        val tabType = tabChanged.params.firstOrNull()
            ?: throw PatchException("Tab change no longer takes the tabs")
        if (tabChanged.params.take(2) != listOf(tabType, tabType)) throw PatchException("Tab change no longer takes the old and new tabs")
        val tabInit = classDefBy(tabType).methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions?.toList()
            ?: throw PatchException("Tab enum has no static initialiser")
        // Each tab constant is stored right after its name is loaded.
        fun tabConstant(name: String): FieldReference {
            val nameAt = tabInit.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == name
            }
            if (nameAt < 0) throw PatchException("Tab enum has no $name tab")
            return tabInit.drop(nameAt).firstNotNullOfOrNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                    instruction.opcode == Opcode.SPUT_OBJECT && it.type == tabType
                }
            } ?: throw PatchException("Tab enum does not store its $name tab")
        }
        val homeTab = tabConstant("FEED")
        val messagesTab = tabConstant("DIRECT")
        // isCurrentTab(tab): the controller's one (tab)Z method the tab change calls.
        val isCurrentTab = tabChanged.instructions.mapNotNull { instruction ->
            methodRef((instruction as? ReferenceInstruction)?.reference)?.takeIf {
                instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == tabControllerType &&
                    it.returnType == "Z" && it.parameterTypes.map { p -> p.toString() } == listOf(tabType)
            }
        }.distinctBy { it.name }.singleOrNull() ?: throw PatchException("Tab change no longer checks the current tab")

        val tabController = mutableClassDefBy(tabControllerType)
        tabController.addBridge(
            "feurstagramMessagesIsMainPage", listOf(tabControllerType), "Z", registers = 2,
            """
                invoke-static { }, $QUOICOUVIEW->messagesIsMainPage()Z
                move-result v0
                if-eqz v0, :no
                sget-object v0, $tabType->${messagesTab.name}:$tabType
                invoke-virtual { p0, v0 }, $tabControllerType->${isCurrentTab.name}($tabType)Z
                move-result v0
                :no
                return v0
            """,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        )

        // The swipe navigation's check that a swipe from the main page may go on:
        // the controller's no-argument boolean its gesture check calls.
        val swipeAllowedName = classDefBy(SWIPE_CONTAINER).methods.filter {
            it.params == listOf("Landroid/view/MotionEvent;") && it.returnType == "Z" && !it.name.startsWith("on")
        }.flatMap { method ->
            method.implementation?.instructions?.mapNotNull { instruction ->
                methodRef((instruction as? ReferenceInstruction)?.reference)?.takeIf {
                    instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == tabControllerType &&
                        it.parameterTypes.isEmpty() && it.returnType == "Z"
                }
            }.orEmpty()
        }.map { it.name }.distinct().singleOrNull()
            ?: throw PatchException("Swipe navigation no longer asks the tab controller about the main page")
        val swipeAllowed = tabController.methods.single {
            it.name == swipeAllowedName && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }

        listOf(tabChanged, swipeAllowed).forEach { method ->
            if (AccessFlags.STATIC.isSet(method.accessFlags)) throw PatchException("${method.name} is no longer an instance method")
            val homeChecks = method.instructions.filter { instruction ->
                val index = instruction.location.index
                instruction.opcode == Opcode.INVOKE_VIRTUAL && index > 0 &&
                    methodRef((instruction as ReferenceInstruction).reference)?.let {
                        it.definingClass == tabControllerType && it.name == isCurrentTab.name
                    } == true &&
                    method.instructions[index - 1].let { load ->
                        load.opcode == Opcode.SGET_OBJECT &&
                            ((load as ReferenceInstruction).reference as FieldReference).let {
                                it.definingClass == homeTab.definingClass && it.name == homeTab.name
                            } &&
                            (load as OneRegisterInstruction).registerA == (instruction as FiveRegisterInstruction).registerD
                    }
            }.map { method.instructions[it.location.index + 1] }.filter { it.opcode == Opcode.MOVE_RESULT }
            if (homeChecks.isEmpty()) throw PatchException("${method.name} no longer checks for Home")
            homeChecks.reversed().forEach { result ->
                val register = (result as OneRegisterInstruction).registerA
                method.addInstructionsWithLabels(
                    result.location.index + 1,
                    """
                        if-nez v$register, :home
                        invoke-static/range { p0 .. p0 }, $tabControllerType->feurstagramMessagesIsMainPage($tabControllerType)Z
                        move-result v$register
                    """,
                    ExternalLabel("home", method.instructions[result.location.index + 1]),
                )
            }
        }

        // The swipe navigation only takes a swipe nothing under the finger can
        // scroll, and the tab pager can always scroll onto the hidden pages left of
        // Messages: its answer goes through Quoicouview.canScrollHorizontally.
        val intercept = classDefBy(SWIPE_CONTAINER).methods.singleOrNull { it.name == "onInterceptTouchEvent" }
            ?: throw PatchException("Swipe navigation has no onInterceptTouchEvent")
        val scrollCheck = intercept.implementation!!.instructions.mapNotNull { instruction ->
            methodRef((instruction as? ReferenceInstruction)?.reference)?.takeIf {
                (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    it.returnType == "Z" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/view/View;", "I", "I", "I", "Z", "Z")
            }
        }.distinctBy { it.definingClass + it.name }.singleOrNull()
            ?: throw PatchException("Swipe navigation no longer checks what can scroll under the finger")
        mutableClassDefBy(scrollCheck.definingClass).methods.single {
            it.name == scrollCheck.name && it.params == scrollCheck.parameterTypes.map { p -> p.toString() }
        }.apply {
            val calls = instructions.filter {
                it.opcode == Opcode.INVOKE_VIRTUAL && methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                    ref.definingClass == "Landroid/view/View;" && ref.name == "canScrollHorizontally"
                } == true
            }
            if (calls.isEmpty()) throw PatchException("Scroll check no longer asks canScrollHorizontally")
            calls.forEach { call ->
                val registers = call as FiveRegisterInstruction
                replaceInstruction(
                    call.location.index,
                    "invoke-static { v${registers.registerC}, v${registers.registerD} }, " +
                        "$QUOICOUVIEW->canScrollHorizontally(Landroid/view/View;I)Z",
                )
            }
        }

        // --- The stories row goes under the search bar --------------------------------

        // The inbox's search bar is drawn by composables that log themselves as
        // "search_row" and take the row's list model: each list model such a method
        // takes is a search bar's (Instagram has a few variants of it).
        // Quoicouview.isSearchRow is replaced by a check for each of them (its stub
        // may have no register to spare). Found none: the stub stays, and the
        // stories row stays first, above the search bar.
        val searchRowParams = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            classDef.methods.forEach { method ->
                val logsSearchRow = method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "search_row"
                } == true
                if (logsSearchRow) searchRowParams += method.params.filter { it.startsWith("L") }
            }
        }
        val searchModels = searchRowParams.filter { isListModel(it, modelType) }.sorted()
        if (searchModels.isNotEmpty()) mutableClassDefBy(QUOICOUVIEW).apply {
            val stub = methods.singleOrNull {
                it.name == "isSearchRow" && it.params == listOf("Ljava/lang/Object;") && it.returnType == "Z"
            } ?: throw PatchException("Extension has no isSearchRow(Object) stub")
            methods.remove(stub)
            addBridge(
                "isSearchRow", listOf("Ljava/lang/Object;"), "Z", registers = 2,
                searchModels.joinToString("\n") { "instance-of v0, p0, $it\nif-nez v0, :search" } +
                    """
                        const/4 v0, 0x0
                        return v0
                        :search
                        const/4 v0, 0x1
                        return v0
                    """,
                stub.accessFlags,
            )
        }
    }
}

/** The adapter's method applying a list update: submit(List) wraps the list and calls it. */
internal fun app.morphe.patcher.patch.BytecodePatchContext.igAdapterApply(): MutableMethod {
    val adapterType = IgRecyclerViewAdapterFingerprint.classDef.type
    // The inbox applies its updates directly, so the hook goes on apply, not submit.
    val submit = classDefBy(adapterType).methods.singleOrNull {
        it.returnType == "V" && it.params == listOf("Ljava/util/List;")
    } ?: throw PatchException("Adapter has no submit(List)")
    val apply = submit.implementation!!.instructions.mapNotNull {
        methodRef((it as? ReferenceInstruction)?.reference)
    }.singleOrNull {
        it.definingClass == adapterType && it.parameterTypes.size == 1 && it.returnType == "V"
    } ?: throw PatchException("Adapter submit(List) does not apply an update")
    return mutableClassDefBy(adapterType).methods.single {
        it.name == apply.name && it.params == apply.parameterTypes.map { p -> p.toString() }
    }
}

/** The list-model interface of the adapter's row types. */
internal fun app.morphe.patcher.patch.BytecodePatchContext.igListModelType(): String {
    val definitionType = classDefBy(IgRecyclerViewAdapterFingerprint.method.params.single()).methods.singleOrNull {
        it.name != "<init>" && it.returnType == "V" && it.parameterTypes.size == 1 &&
            it.parameterTypes[0].toString().let { type ->
                !type.startsWith("Ljava/") && !type.startsWith("Landroid/")
            }
    }?.params?.single() ?: throw PatchException("Adapter builder has no add-definition method")
    val definition = classDefBy(definitionType)
    val holderType = definition.methods.singleOrNull {
        it.params == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;")
    }?.returnType ?: throw PatchException("Row definition has no create method")
    return definition.methods.singleOrNull {
        it.returnType == "V" && it.parameterTypes.size == 2 && it.params[0] == holderType
    }?.params?.get(1) ?: throw PatchException("Row definition has no bind method")
}

/** Whether a class implements Instagram's list-model interface, directly or not. */
internal fun app.morphe.patcher.patch.BytecodePatchContext.isListModel(type: String, modelType: String): Boolean {
    fun extendsModel(interfaceType: String): Boolean = interfaceType == modelType ||
        (runCatching { classDefBy(interfaceType) }.getOrNull()?.interfaces?.any { extendsModel(it) } == true)
    var current: String? = type
    while (current != null && current != "Ljava/lang/Object;") {
        val classDef = runCatching { classDefBy(current) }.getOrNull() ?: return false
        if (classDef.interfaces.any { extendsModel(it) }) return true
        current = classDef.superclass
    }
    return false
}

/** Abstract methods of an interface and of the interfaces it extends. */
private fun app.morphe.patcher.patch.BytecodePatchContext.abstractMethods(type: String): List<Method> {
    val classDef: ClassDef = classDefBy(type)
    return classDef.methods.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) } +
        classDef.interfaces.flatMap { abstractMethods(it) }
}

/** Add a method (public by default) with the given smali body to an extension class. */
internal fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    smali: String,
    accessFlags: Int = AccessFlags.PUBLIC.value,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, smali.trimIndent()) },
    )
}

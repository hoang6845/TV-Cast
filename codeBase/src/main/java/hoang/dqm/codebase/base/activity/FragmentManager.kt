package hoang.dqm.codebase.base.activity

object FragmentManager {

    private val fragments by lazy {
        linkedSetOf<BaseFragment<*, *>>()
    }

    fun addFragment(fragment: BaseFragment<*, *>) {
        fragments.add(fragment)
    }

    fun removeFragment(fragment: BaseFragment<*, *>) {
        fragments.remove(fragment)
    }

    fun getTopFragmentOrNull(): BaseFragment<*, *>? {
        return try {
            fragments.lastOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearAll() {
        fragments.clear()
    }
}

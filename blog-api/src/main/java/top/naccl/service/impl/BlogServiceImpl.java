package top.naccl.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.naccl.config.RedisKeyConfig;
import top.naccl.entity.Blog;
import top.naccl.exception.NotFoundException;
import top.naccl.exception.PersistenceException;
import top.naccl.mapper.BlogMapper;
import top.naccl.model.dto.BlogView;
import top.naccl.model.dto.BlogVisibility;
import top.naccl.model.vo.ArchiveBlog;
import top.naccl.model.vo.BlogDetail;
import top.naccl.model.vo.BlogInfo;
import top.naccl.model.vo.NewBlog;
import top.naccl.model.vo.PageResult;
import top.naccl.model.vo.RandomBlog;
import top.naccl.model.vo.SearchBlog;
import top.naccl.service.BlogService;
import top.naccl.service.RedisService;
import top.naccl.service.TagService;
import top.naccl.task.RedisSyncScheduleTask;
import top.naccl.util.JacksonUtils;
import top.naccl.util.markdown.MarkdownUtils;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 博客文章业务层实现
 * @Author: Naccl
 * @Date: 2020-07-29
 */
@Service
public class BlogServiceImpl implements BlogService {
	@Autowired
	BlogMapper blogMapper;
	@Autowired
	TagService tagService;
	@Autowired
	RedisService redisService;
	@Autowired
	RedisSyncScheduleTask redisSyncScheduleTask;
	//随机博客显示5条
	private static final int randomBlogLimitNum = 5;
	//最新推荐博客显示3条
	private static final int newBlogPageSize = 3;
	//每页显示5条博客简介
	private static final int pageSize = 5;
	//博客简介列表排序方式
	private static final String orderBy = "is_top desc, create_time desc";
	//私密博客提示
	private static final String PRIVATE_BLOG_DESCRIPTION = "此文章受密码保护！";

	/**
	 * 项目启动时，保存所有博客的浏览量到Redis
	 */
	@PostConstruct
	private void saveBlogViewsToRedis() {
		String redisKey = RedisKeyConfig.BLOG_VIEWS_MAP;
		//Redis中没有存储博客浏览量的Hash
		if (!redisService.hasKey(redisKey)) {
			//从数据库中读取并存入Redis
			Map<Long, Integer> blogViewsMap = getBlogViewsMap();
			redisService.saveMapToHash(redisKey, blogViewsMap);
		}
	}

	@Override
	public List<Blog> getListByTitleAndCategoryId(String title, Integer categoryId) {
		return blogMapper.getListByTitleAndCategoryId(title, categoryId);
	}

	@Override
	public List<SearchBlog> getSearchBlogListByQueryAndIsPublished(String query) {
		List<SearchBlog> searchBlogs = blogMapper.getSearchBlogListByQueryAndIsPublished(query);
		for (SearchBlog searchBlog : searchBlogs) {
			String content = searchBlog.getContent();
			int contentLength = content.length();
			int index = content.indexOf(query) - 10;
			index = index < 0 ? 0 : index;
			int end = index + 21;//以关键字字符串为中心返回21个字
			end = end > contentLength - 1 ? contentLength - 1 : end;
			searchBlog.setContent(content.substring(index, end));
		}
		return searchBlogs;
	}

	@Override
	public List<Blog> getIdAndTitleList() {
		return blogMapper.getIdAndTitleList();
	}

	@Override
	public List<NewBlog> getNewBlogListByIsPublished() {
		String redisKey = RedisKeyConfig.NEW_BLOG_LIST;
		List<NewBlog> newBlogListFromRedis = redisService.getListByValue(redisKey);
		if (newBlogListFromRedis != null) {
			return newBlogListFromRedis;
		}
		PageHelper.startPage(1, newBlogPageSize);
		List<NewBlog> newBlogList = blogMapper.getNewBlogListByIsPublished();
		for (NewBlog newBlog : newBlogList) {
			if (!"".equals(newBlog.getPassword())) {
				newBlog.setPrivacy(true);
				newBlog.setPassword("");
			} else {
				newBlog.setPrivacy(false);
			}
		}
		redisService.saveListToValue(redisKey, newBlogList);
		return newBlogList;
	}

	@Override
	public PageResult<BlogInfo> getBlogInfoListByIsPublished(Integer pageNum) {
		String redisKey = RedisKeyConfig.HOME_BLOG_INFO_LIST;
		//redis已有当前页缓存
		PageResult<BlogInfo> pageResultFromRedis = redisService.getBlogInfoPageResultByHash(redisKey, pageNum);
		if (pageResultFromRedis != null) {
			setBlogViewsFromRedisToPageResult(pageResultFromRedis);
			return pageResultFromRedis;
		}
		//redis没有缓存，从数据库查询，并添加缓存
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<BlogInfo> blogInfos = processBlogInfosPassword(blogMapper.getBlogInfoListByIsPublished());
		PageInfo<BlogInfo> pageInfo = new PageInfo<>(blogInfos);
		PageResult<BlogInfo> pageResult = new PageResult<>(pageInfo.getPages(), pageInfo.getList());
		setBlogViewsFromRedisToPageResult(pageResult);
		//添加首页缓存
		redisService.saveKVToHash(redisKey, pageNum, pageResult);
		return pageResult;
	}

	/**
	 * 将pageResult中博客对象的浏览量设置为Redis中的最新值
	 *
	 * @param pageResult
	 */
	private void setBlogViewsFromRedisToPageResult(PageResult<BlogInfo> pageResult) {
		String redisKey = RedisKeyConfig.BLOG_VIEWS_MAP;
		List<BlogInfo> blogInfos = pageResult.getList();
		for (int i = 0; i < blogInfos.size(); i++) {
			BlogInfo blogInfo = JacksonUtils.convertValue(blogInfos.get(i), BlogInfo.class);
			Long blogId = blogInfo.getId();
			int view = (int) redisService.getValueByHashKey(redisKey, blogId);
			blogInfo.setViews(view);
			blogInfos.set(i, blogInfo);
		}
	}

	@Override
	public PageResult<BlogInfo> getBlogInfoListByCategoryNameAndIsPublished(String categoryName, Integer pageNum) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<BlogInfo> blogInfos = processBlogInfosPassword(blogMapper.getBlogInfoListByCategoryNameAndIsPublished(categoryName));
		PageInfo<BlogInfo> pageInfo = new PageInfo<>(blogInfos);
		PageResult<BlogInfo> pageResult = new PageResult<>(pageInfo.getPages(), pageInfo.getList());
		setBlogViewsFromRedisToPageResult(pageResult);
		return pageResult;
	}

	@Override
	public PageResult<BlogInfo> getBlogInfoListByTagNameAndIsPublished(String tagName, Integer pageNum) {
		PageHelper.startPage(pageNum, pageSize, orderBy);
		List<BlogInfo> blogInfos = processBlogInfosPassword(blogMapper.getBlogInfoListByTagNameAndIsPublished(tagName));
		PageInfo<BlogInfo> pageInfo = new PageInfo<>(blogInfos);
		PageResult<BlogInfo> pageResult = new PageResult<>(pageInfo.getPages(), pageInfo.getList());
		setBlogViewsFromRedisToPageResult(pageResult);
		return pageResult;
	}

	private List<BlogInfo> processBlogInfosPassword(List<BlogInfo> blogInfos) {
		for (BlogInfo blogInfo : blogInfos) {
			if (!"".equals(blogInfo.getPassword())) {
				blogInfo.setPrivacy(true);
				blogInfo.setPassword("");
				blogInfo.setDescription(PRIVATE_BLOG_DESCRIPTION);
			} else {
				blogInfo.setPrivacy(false);
				blogInfo.setDescription(MarkdownUtils.markdownToHtmlExtensions(blogInfo.getDescription()));
			}
			blogInfo.setTags(tagService.getTagListByBlogId(blogInfo.getId()));
		}
		return blogInfos;
	}

	@Override
	public Map<String, Object> getArchiveBlogAndCountByIsPublished() {
		String redisKey = RedisKeyConfig.ARCHIVE_BLOG_MAP;
		Map<String, Object> mapFromRedis = redisService.getMapByValue(redisKey);
		if (mapFromRedis != null) {
			return mapFromRedis;
		}
		Map<String, Object> map = new HashMap<>();
		List<String> groupYearMonth = blogMapper.getGroupYearMonthByIsPublished();
		Map<String, List<ArchiveBlog>> archiveBlogMap = new LinkedHashMap<>();
		for (String s : groupYearMonth) {
			List<ArchiveBlog> archiveBlogs = blogMapper.getArchiveBlogListByYearMonthAndIsPublished(s);
			for (ArchiveBlog archiveBlog : archiveBlogs) {
				if (!"".equals(archiveBlog.getPassword())) {
					archiveBlog.setPrivacy(true);
					archiveBlog.setPassword("");
				} else {
					archiveBlog.setPrivacy(false);
				}
			}
			archiveBlogMap.put(s, archiveBlogs);
		}
		Integer count = countBlogByIsPublished();
		map.put("blogMap", archiveBlogMap);
		map.put("count", count);
		redisService.saveMapToValue(redisKey, map);
		return map;
	}

	@Override
	public List<RandomBlog> getRandomBlogListByLimitNumAndIsPublishedAndIsRecommend() {
		List<RandomBlog> randomBlogs = blogMapper.getRandomBlogListByLimitNumAndIsPublishedAndIsRecommend(randomBlogLimitNum);
		for (RandomBlog randomBlog : randomBlogs) {
			if (!"".equals(randomBlog.getPassword())) {
				randomBlog.setPrivacy(true);
				randomBlog.setPassword("");
			} else {
				randomBlog.setPrivacy(false);
			}
		}
		return randomBlogs;
	}

	private Map<Long, Integer> getBlogViewsMap() {
		List<BlogView> blogViewList = blogMapper.getBlogViewsList();
		Map<Long, Integer> blogViewsMap = new HashMap<>();
		for (BlogView blogView : blogViewList) {
			blogViewsMap.put(blogView.getId(), blogView.getViews());
		}
		return blogViewsMap;
	}

	@Transactional
	@Override
	public void deleteBlogById(Long id) {
		if (blogMapper.deleteBlogById(id) != 1) {
			throw new NotFoundException("该博客不存在");
		}
		deleteBlogRedisCache();
		redisService.deleteByHashKey(RedisKeyConfig.BLOG_VIEWS_MAP, id);
	}

	@Transactional
	@Override
	public void deleteBlogTagByBlogId(Long blogId) {
		if (blogMapper.deleteBlogTagByBlogId(blogId) == 0) {
			throw new PersistenceException("维护博客标签关联表失败");
		}
	}

	@Transactional
	@Override
	public void saveBlog(top.naccl.model.dto.Blog blog) {
		if (blogMapper.saveBlog(blog) != 1) {
			throw new PersistenceException("添加博客失败");
		}
		redisService.saveKVToHash(RedisKeyConfig.BLOG_VIEWS_MAP, blog.getId(), 0);
		deleteBlogRedisCache();
	}

	@Transactional
	@Override
	public void saveBlogTag(Long blogId, Long tagId) {
		if (blogMapper.saveBlogTag(blogId, tagId) != 1) {
			throw new PersistenceException("维护博客标签关联表失败");
		}
	}

	@Transactional
	@Override
	public void updateBlogRecommendById(Long blogId, Boolean recommend) {
		if (blogMapper.updateBlogRecommendById(blogId, recommend) != 1) {
			throw new PersistenceException("操作失败");
		}
	}

	@Transactional
	@Override
	public void updateBlogVisibilityById(Long blogId, BlogVisibility blogVisibility) {
		if (blogMapper.updateBlogVisibilityById(blogId, blogVisibility) != 1) {
			throw new PersistenceException("操作失败");
		}
		redisService.deleteCacheByKey(RedisKeyConfig.HOME_BLOG_INFO_LIST);
		redisService.deleteCacheByKey(RedisKeyConfig.NEW_BLOG_LIST);
		redisService.deleteCacheByKey(RedisKeyConfig.ARCHIVE_BLOG_MAP);
	}

	@Transactional
	@Override
	public void updateBlogTopById(Long blogId, Boolean top) {
		if (blogMapper.updateBlogTopById(blogId, top) != 1) {
			throw new PersistenceException("操作失败");
		}
		redisService.deleteCacheByKey(RedisKeyConfig.HOME_BLOG_INFO_LIST);
	}

	@Override
	public void updateViewsToRedis(Long blogId) {
		redisService.incrementByHashKey(RedisKeyConfig.BLOG_VIEWS_MAP, blogId, 1);
	}

	@Transactional
	@Override
	public void updateViews(Long blogId, Integer views) {
		if (blogMapper.updateViews(blogId, views) != 1) {
			throw new PersistenceException("更新失败");
		}
	}

	@Override
	public Blog getBlogById(Long id) {
		Blog blog = blogMapper.getBlogById(id);
		if (blog == null) {
			throw new NotFoundException("博客不存在");
		}
		//将浏览量设置为Redis中的最新值
		int view = (int) redisService.getValueByHashKey(RedisKeyConfig.BLOG_VIEWS_MAP, blog.getId());
		blog.setViews(view);
		return blog;
	}

	@Override
	public BlogDetail getBlogByIdAndIsPublished(Long id) {
		BlogDetail blog = blogMapper.getBlogByIdAndIsPublished(id);
		if (blog == null) {
			throw new NotFoundException("该博客不存在");
		}
		blog.setContent(MarkdownUtils.markdownToHtmlExtensions(blog.getContent()));
		//将浏览量设置为Redis中的最新值
		int view = (int) redisService.getValueByHashKey(RedisKeyConfig.BLOG_VIEWS_MAP, blog.getId());
		blog.setViews(view);
		return blog;
	}

	@Override
	public String getBlogPassword(Long blogId) {
		return blogMapper.getBlogPassword(blogId);
	}

	@Transactional
	@Override
	public void updateBlog(top.naccl.model.dto.Blog blog) {
		if (blogMapper.updateBlog(blog) != 1) {
			throw new PersistenceException("更新博客失败");
		}
		deleteBlogRedisCache();
		redisService.saveKVToHash(RedisKeyConfig.BLOG_VIEWS_MAP, blog.getId(), blog.getViews());
	}

	@Override
	public int countBlogByIsPublished() {
		return blogMapper.countBlogByIsPublished();
	}

	@Override
	public int countBlogByCategoryId(Long categoryId) {
		return blogMapper.countBlogByCategoryId(categoryId);
	}

	@Override
	public int countBlogByTagId(Long tagId) {
		return blogMapper.countBlogByTagId(tagId);
	}

	@Override
	public Boolean getCommentEnabledByBlogId(Long blogId) {
		return blogMapper.getCommentEnabledByBlogId(blogId);
	}

	@Override
	public Boolean getPublishedByBlogId(Long blogId) {
		return blogMapper.getPublishedByBlogId(blogId);
	}

	/**
	 * 删除首页缓存、最新推荐缓存、归档页面缓存、博客浏览量缓存
	 */
	private void deleteBlogRedisCache() {
		redisService.deleteCacheByKey(RedisKeyConfig.HOME_BLOG_INFO_LIST);
		redisService.deleteCacheByKey(RedisKeyConfig.NEW_BLOG_LIST);
		redisService.deleteCacheByKey(RedisKeyConfig.ARCHIVE_BLOG_MAP);
	}
}

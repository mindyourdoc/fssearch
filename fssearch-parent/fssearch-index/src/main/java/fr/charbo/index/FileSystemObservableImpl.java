package fr.charbo.index;

import fr.charbo.index.bean.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class FileSystemObservableImpl implements FileSystemObservable {
  private static final Logger LOG = LoggerFactory.getLogger(FileSystemObservableImpl.class);

  private FileIndexer fileIndexer;

  private Date reference = new Date();

  @Autowired
  public FileSystemObservableImpl(FileIndexer fileIndexer) {
    this.fileIndexer = fileIndexer;
  }

  /**
	 * Returns an {@link Observable} of {@link WatchEvent}s from a
	 * {@link WatchService}.
	 *
	 * @param watchService
	 * @return
	 */
  private final Observable<WatchKey> from(WatchService watchService) {
		return Observable.create(new WatchServiceOnSubscribe(watchService));
	}


	@SafeVarargs
	private final Observable<DocumentObserved> from(final Path file, Kind<?>... kinds) {

		return watchService(file, kinds).flatMap(TO_WATCH_EVENTS).flatMap(new Func1<WatchKey, Observable<WatchEvent<?>>>() {

      @Override
      public Observable<WatchEvent<?>> call(WatchKey key) {
        return Observable.create(new OnSubscribe<WatchEvent<?>>() {

          @Override
          public void call(Subscriber<? super WatchEvent<?>> subscriber) {
            for (WatchEvent<?> event : key.pollEvents()) {
              subscriber.onNext(event);
            }

          }

        });
      }
		}).filter(new Func1<WatchEvent<?>, Boolean>() {
      @Override
      public Boolean call(WatchEvent<?> watchEvent) {
        Path path = (Path) watchEvent.context();

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*.{tx*,doc*}");
        PathMatcher exclude = FileSystems.getDefault().getPathMatcher("glob:~$_*");
        return matcher.matches(path) && !exclude.matches(path);
      }
    }).flatMap(new Func1<WatchEvent<?>, Observable<DocumentObserved>>() {
      @Override
      public Observable<DocumentObserved> call(WatchEvent<?> event) {
        return Observable.create(new OnSubscribe<DocumentObserved>() {

          @Override
          public void call(Subscriber<? super DocumentObserved> subscriber) {
            Path name = (Path) event.context();

            Path child = file.resolve(name);
            Kind<?> kind = event.kind();

            DocumentObserved documentObserved = new DocumentObserved();
            documentObserved.setName(event.context() + "");
            documentObserved.setPath(child);
            documentObserved.setKind(kind);

            subscriber.onNext(documentObserved);
          }

        });
      }
    });
	}

	/**
	 * Creates a {@link WatchService} on subscribe for the given file and event
	 * kinds.
	 *
	 * @param file
	 * @param kinds
	 * @return
	 */
	@SafeVarargs
  private final Observable<WatchService> watchService(final Path path, final Kind<?>...kinds){
      return Observable.create(new OnSubscribe<WatchService>() {
        @Override
        public void call(Subscriber<? super WatchService> subscriber) {
          try {
            WatchService watchService = path.getFileSystem().newWatchService();
            path.register(watchService, kinds);
            subscriber.onNext(watchService);
            subscriber.onCompleted();
          } catch (IOException e) {
            subscriber.onError(e);
          }
        }
      });
	}

	private final Path getBasePath(final File file) {
		final Path path;
		if (file.exists() && file.isDirectory())
			path = Paths.get(file.toURI());
		else
			path = Paths.get(file.getParentFile().toURI());
		return path;
	}



	private class WatchServiceOnSubscribe implements OnSubscribe<WatchKey> {
		private final WatchService watchService;
		private final AtomicBoolean subscribed = new AtomicBoolean(true);

		WatchServiceOnSubscribe(WatchService watchService) {
			this.watchService = watchService;
		}

		@Override
		public void call(Subscriber<? super WatchKey> subscriber) {
			if (!subscribed.get()) {
				subscriber
						.onError(new RuntimeException(
								"WatchService closed. You can only subscribe once to a WatchService."));
				return;
			}
			subscriber.add(createSubscriptionToCloseWatchService(watchService,
					subscribed, subscriber));
			emitEvents(subscriber);
		}

		private void emitEvents(Subscriber<? super WatchKey> subscriber) {
			// get the first event before looping
			WatchKey key = nextKey(subscriber);

			while (key != null) {
				subscriber.onNext(key);
				boolean valid = key.reset();
				if (!valid && subscribed.get()) {
					subscriber.onCompleted();
					return;
				} else if (!valid)
					return;
				key = nextKey(subscriber);
			}
		}

		private WatchKey nextKey(Subscriber<? super WatchKey> subscriber) {
			try {
				return watchService.take();
			} catch (ClosedWatchServiceException e) {
				// must have unsubscribed
				if (subscribed.get())
					subscriber.onCompleted();
				return null;
			} catch (InterruptedException e) {
				try {
					watchService.close();
				} catch (IOException e1) {
					// do nothing
				}
				return null;
			}
		}
	}

	private final Subscription createSubscriptionToCloseWatchService(final WatchService watchService, final AtomicBoolean subscribed,
			final Subscriber<? super WatchKey> subscriber) {
		return new Subscription() {
			@Override
			public void unsubscribe() {
				try {
					watchService.close();
					subscribed.set(false);
				} catch (ClosedWatchServiceException e) {
					// do nothing
					subscribed.set(false);
				} catch (IOException e) {
					// do nothing
					subscribed.set(false);
				}
			}

			@Override
			public boolean isUnsubscribed() {
				return !subscribed.get();
			}
		};
	}

	private final Func1<WatchService, Observable<WatchKey>> TO_WATCH_EVENTS = new Func1<WatchService, Observable<WatchKey>>() {
		@Override
		public Observable<WatchKey> call(WatchService watchService) {
			return from(watchService);
		}
	};


  private class ActionIndex implements Action1<DocumentObserved> {

    @Override
    public void call(DocumentObserved event) {
      LOG.info("event detected {}", event.getKind());
      Document document = new Document();
      document.setPath(event.getPath().toString());
      document.setTitle(event.getName());
      if (event.getKind() == StandardWatchEventKinds.ENTRY_DELETE) {
        if (Files.notExists( event.getPath())) {
          LOG.info("delete {}", document.getTitle());
          fileIndexer.deleteDocument(document);
        }
      } else if (event.getKind() == StandardWatchEventKinds.ENTRY_MODIFY) {
          LOG.info("updqte {}", document.getTitle());
          fileIndexer.indexDocument(document);
      }
    }
  }

  @Override
  public void subscribeActionIndex(Path file) {
    from(file, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.OVERFLOW, StandardWatchEventKinds.ENTRY_DELETE)
    .distinct().subscribe(new ActionIndex());
  }


  private class DocumentObserved {
    private String name;
    private Path path;
    private WatchEvent.Kind<?> kind;

    public DocumentObserved() {
      super();
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public WatchEvent.Kind<?> getKind() {
      return kind;
    }

    public void setKind(WatchEvent.Kind<?> kind) {
      this.kind = kind;
    }

    public Path getPath() {
      return path;
    }

    public void setPath(Path path) {
      this.path = path;
    }

    @Override
    public int hashCode() {
      Date now = new Date();
      int flag = 0;
      if (getKind() == StandardWatchEventKinds.ENTRY_DELETE || now.getTime() - reference.getTime() > 10000) {
        flag = new Random().nextInt();
        reference = now;
      }

      return name.hashCode() + flag;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null || !(obj instanceof DocumentObserved)) {
        return false;
      }
      DocumentObserved other = (DocumentObserved) obj;

      return this.name.equals(other.name);
    }

    @Override
    public String toString() {
      return "MonEvent{" +
              "kind=" + kind +
              ", name='" + name + '\'' +
              ", path=" + path +
              '}';
    }
  }


}
